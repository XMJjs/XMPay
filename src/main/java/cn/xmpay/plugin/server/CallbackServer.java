package cn.xmpay.plugin.server;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.api.ZPayAPI;
import cn.xmpay.plugin.config.XMPayConfig;
import cn.xmpay.plugin.model.PayOrder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * 内置HTTP回调服务器
 * 监听易支付平台的异步通知(notify_url)
 *
 * 支付成功后，易支付平台会向此服务器发送 GET 请求，
 * 携带订单号、金额、签名等参数。
 */
public class CallbackServer {

    private final XMPayPlugin plugin;
    private HttpServer server;

    public CallbackServer(XMPayPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        XMPayConfig cfg = plugin.getXMPayConfig();
        int port = cfg.getHttpServerPort();
        String bind = cfg.getHttpServerBind();
        String path = cfg.getCallbackPath();

        try {
            server = HttpServer.create(
                    new InetSocketAddress(bind, port), 0);
            server.createContext(path, new NotifyHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();

            plugin.getLogger().info("XMPay 回调服务器已启动: " + bind + ":" + port + path);
            plugin.getLogger().info("Notify URL: " + cfg.buildNotifyUrl());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "无法启动回调服务器（端口 " + port + " 可能被占用）", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(2);
            plugin.getLogger().info("XMPay 回调服务器已停止。");
        }
    }

    /**
     * 易支付回调处理器
     */
    private class NotifyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String response;

            try {
                // 支持 GET 和 POST
                Map<String, String> params;
                if ("GET".equalsIgnoreCase(method)) {
                    String query = exchange.getRequestURI().getQuery();
                    params = parseQuery(query);
                } else if ("POST".equalsIgnoreCase(method)) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    params = parseQuery(new String(body, StandardCharsets.UTF_8));
                } else {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }

                // IP白名单检查
                XMPayConfig cfg = plugin.getXMPayConfig();
                List<String> ipWhitelist = cfg.getNotifyIpWhitelist();
                if (!ipWhitelist.isEmpty()) {
                    String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
                    if (!ipWhitelist.contains(remoteIp)) {
                        plugin.getLogger().warning("回调IP不在白名单: " + remoteIp);
                        sendResponse(exchange, 403, "Forbidden");
                        return;
                    }
                }

                response = processNotify(params);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "处理回调通知时发生错误", e);
                response = "error";
            }

            sendResponse(exchange, 200, response);
        }

        /**
         * 处理支付通知逻辑
         * 成功返回 "success"，失败返回 "fail"
         */
        private String processNotify(Map<String, String> params) {
            XMPayConfig cfg = plugin.getXMPayConfig();

            if (plugin.getXMPayConfig().isDebug()) {
                plugin.getLogger().info("[Callback] 收到通知: " + params);
            }

            // 1. 检查必要参数
            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            String tradeStatus = params.get("trade_status");
            String money = params.get("money");
            String sign = params.get("sign");
            String pid = params.get("pid");

            if (outTradeNo == null || tradeStatus == null || sign == null) {
                plugin.getLogger().warning("回调参数缺失: " + params.keySet());
                return "fail";
            }

            // 2. 校验商户ID
            if (!cfg.getPid().equals(pid)) {
                plugin.getLogger().warning("回调商户ID不匹配: " + pid);
                return "fail";
            }

            // 3. 验证签名
            if (cfg.isVerifySign()) {
                Map<String, String> signParams = new HashMap<>(params);
                signParams.remove("sign");
                signParams.remove("sign_type");
                if (!plugin.getZPayAPI().verifyNotifySign(signParams, sign)) {
                    plugin.getLogger().warning("回调签名验证失败！outTradeNo=" + outTradeNo);
                    return "fail";
                }
            }

            // 4. 只处理成功状态
            if (!"TRADE_SUCCESS".equals(tradeStatus)) {
                return "success"; // 非成功状态也要返回success防止重试
            }

            // 5. 查找订单
            PayOrder order = plugin.getOrderManager().getOrder(outTradeNo);
            if (order == null) {
                // 检查是否已处理过
                if (plugin.getOrderManager().isOrderInHistory(outTradeNo)) {
                    plugin.getLogger().info("重复通知，已处理: " + outTradeNo);
                    return "success";
                }
                plugin.getLogger().warning("收到未知订单的回调: " + outTradeNo);
                return "fail";
            }

            // 6. 校验金额
            try {
                double notifyMoney = Double.parseDouble(money);
                double orderMoney = order.getMoney();
                if (Math.abs(notifyMoney - orderMoney) > 0.001) {
                    plugin.getLogger().warning(
                            "金额不匹配！通知=" + notifyMoney + " 订单=" + orderMoney);
                    return "fail";
                }
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("无效金额: " + money);
                return "fail";
            }

            // 7. 标记订单已支付
            plugin.getOrderManager().markPaid(outTradeNo, tradeNo);

            // 8. 在主线程处理支付成功逻辑
            final PayOrder finalOrder = order;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                handlePaymentSuccess(finalOrder);
            });

            return "success";
        }

        private void handlePaymentSuccess(PayOrder order) {
            org.bukkit.entity.Player player = plugin.getServer()
                    .getPlayer(order.getPlayerUUID());

            // 1. 执行经济发放
            boolean economyGiven = false;
            if (plugin.getEconomyManager() != null && plugin.getEconomyManager().isReady()) {
                double gameAmount = order.getMoney() * plugin.getXMPayConfig().getEconomyRate();
                plugin.getEconomyManager().give(order.getPlayerUUID(),
                        order.getPlayerName(), gameAmount);
                economyGiven = true;
            }

            // 2. 执行套餐指令
            String packageId = order.getPackageId();
            if (packageId != null && !packageId.isEmpty()) {
                for (XMPayConfig.PaymentPackage pkg : plugin.getXMPayConfig().getPackages()) {
                    if (pkg.id.equals(packageId)) {
                        for (String cmd : pkg.commands) {
                            String parsed = cmd
                                    .replace("{player}", order.getPlayerName())
                                    .replace("{amount}", String.format("%.2f",
                                            order.getMoney() * plugin.getXMPayConfig().getEconomyRate()))
                                    .replace("{money}", String.format("%.2f", order.getMoney()))
                                    .replace("{order_no}", order.getOutTradeNo())
                                    .replace("{type}", order.getPayType().getCode());
                            plugin.getServer().dispatchCommand(
                                    plugin.getServer().getConsoleSender(), parsed);
                        }
                        break;
                    }
                }
            }

            // 3. 执行身份权限指令
            if (player != null) {
                String role = "default";
                if (player.hasPermission("xmpay.admin")) {
                    role = "admin";
                } else if (player.hasPermission("xmpay.vip")) {
                    role = "vip";
                }

                List<String> roleCmds = plugin.getXMPayConfig()
                        .getRoleCommands(role, "on-payment-success");
                for (String cmd : roleCmds) {
                    String parsed = cmd
                            .replace("{player}", order.getPlayerName())
                            .replace("{amount}", String.format("%.2f",
                                    order.getMoney() * plugin.getXMPayConfig().getEconomyRate()))
                            .replace("{money}", String.format("%.2f", order.getMoney()))
                            .replace("{order_no}", order.getOutTradeNo())
                            .replace("{type}", order.getPayType().getCode());
                    plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), parsed);
                }
            }

            // 4. 通知玩家
            if (player != null && player.isOnline()) {
                String msg = plugin.getXMPayConfig().getMessage("payment-success")
                        .replace("{amount}", String.format("%.2f",
                                order.getMoney() * plugin.getXMPayConfig().getEconomyRate()))
                        .replace("{money}", String.format("%.2f", order.getMoney()))
                        .replace("{type}", order.getPayType().getDisplayName());
                player.sendMessage(msg);
            }

            plugin.getLogger().info("支付处理完成: 玩家=" + order.getPlayerName()
                    + " 金额=" + order.getMoney() + "元 订单=" + order.getOutTradeNo());
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> map = new LinkedHashMap<>();
            if (query == null || query.isEmpty()) return map;
            for (String pair : query.split("&")) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    try {
                        String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                        String val = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                        map.put(key, val);
                    } catch (Exception ignored) {}
                }
            }
            return map;
        }

        private void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
