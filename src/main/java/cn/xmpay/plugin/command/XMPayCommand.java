package cn.xmpay.plugin.command;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.api.ZPayAPI;
import cn.xmpay.plugin.config.XMPayConfig;
import cn.xmpay.plugin.gui.PaymentGUI;
import cn.xmpay.plugin.model.PayOrder;
import cn.xmpay.plugin.util.CooldownManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * XMPay 主玩家指令处理器
 *
 * 指令列表:
 *   /xmpay              - 打开充值界面
 *   /xmpay help         - 显示帮助
 *   /xmpay pay <金额> [支付方式]  - 自定义金额支付
 *   /xmpay order        - 查看当前订单
 *   /xmpay order cancel - 取消当前订单
 *   /xmpay order query <单号> - 查询指定订单
 *   /xmpay types        - 查看支持的支付方式
 */
public class XMPayCommand implements CommandExecutor, TabCompleter {

    private final XMPayPlugin plugin;
    private final CooldownManager cooldown;

    public XMPayCommand(XMPayPlugin plugin) {
        this.plugin = plugin;
        this.cooldown = new CooldownManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                              String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家执行！");
            return true;
        }

        if (!player.hasPermission("xmpay.use")) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("no-permission"));
            return true;
        }

        // 冷却检查
        int cd = plugin.getXMPayConfig().getCooldown();
        if (cd > 0 && cooldown.isOnCooldown(player.getUniqueId(), cd)) {
            long remaining = cooldown.getRemainingSeconds(player.getUniqueId(), cd);
            player.sendMessage(plugin.getXMPayConfig().getMessage("cooldown")
                    .replace("{seconds}", String.valueOf(remaining)));
            return true;
        }

        if (args.length == 0) {
            // 打开充值GUI
            cooldown.setCooldown(player.getUniqueId());
            openPaymentGUI(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> showHelp(player);
            case "pay" -> handlePay(player, args);
            case "order" -> handleOrder(player, args);
            case "types" -> showTypes(player);
            default -> {
                player.sendMessage(plugin.getXMPayConfig().getMessage("no-permission")
                        .replace("§c你没有权限执行此操作！", "§c未知子命令，使用 /xmpay help 查看帮助"));
            }
        }

        return true;
    }

    private void openPaymentGUI(Player player) {
        if (plugin.getXMPayConfig().isPackagesEnabled()) {
            new PaymentGUI(plugin, player).open();
        } else {
            showHelp(player);
        }
    }

    private void showHelp(Player player) {
        String prefix = XMPayConfig.colorize("&8[&6XM&eP&6ay&8] ");
        player.sendMessage(prefix + XMPayConfig.colorize("&6===== XMPay 帮助 ====="));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay &7- 打开充值界面"));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay pay &b<金额> [支付方式] &7- 自定义金额充值"));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay order &7- 查看当前待处理订单"));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay order cancel &7- 取消当前订单"));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay order query &b<单号> &7- 查询订单状态"));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay types &7- 查看支持的支付方式"));
    }

    private void handlePay(Player player, String[] args) {
        if (!player.hasPermission("xmpay.pay")) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(XMPayConfig.colorize("&c用法: /xmpay pay <金额> [alipay|wxpay]"));
            return;
        }

        // 解析金额
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("invalid-amount")
                    .replace("{min}", String.valueOf(plugin.getXMPayConfig().getMinAmount()))
                    .replace("{max}", String.valueOf(plugin.getXMPayConfig().getMaxAmount())));
            return;
        }

        // 验证金额范围
        double min = plugin.getXMPayConfig().getMinAmount();
        double max = plugin.getXMPayConfig().getMaxAmount();
        if (amount < min || amount > max) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("invalid-amount")
                    .replace("{min}", String.format("%.2f", min))
                    .replace("{max}", String.format("%.2f", max)));
            return;
        }

        // 解析支付方式
        String typeStr = args.length >= 3 ? args[2].toLowerCase()
                : plugin.getXMPayConfig().getDefaultPayType();
        PayOrder.PayType payType = PayOrder.PayType.fromCode(typeStr);

        // 检查支付方式是否启用
        if (!plugin.getXMPayConfig().getEnabledPayTypes()
                .contains(payType.getCode())) {
            player.sendMessage(XMPayConfig.colorize("&c该支付方式未启用，可用: "
                    + String.join(", ", plugin.getXMPayConfig().getEnabledPayTypes())));
            return;
        }

        // 检查是否已有待处理订单
        if (plugin.getOrderManager().hasPendingOrder(player.getUniqueId())) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("already-pending"));
            return;
        }

        cooldown.setCooldown(player.getUniqueId());

        // 在异步线程中创建订单
        String productName = "游戏充值 ¥" + String.format("%.2f", amount);
        initiatePayment(player, productName, amount, payType, null);
    }

    private void handleOrder(Player player, String[] args) {
        if (!player.hasPermission("xmpay.order.query")) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            // 查看当前订单
            PayOrder order = plugin.getOrderManager()
                    .getPlayerActiveOrder(player.getUniqueId());
            if (order == null) {
                player.sendMessage(plugin.getXMPayConfig().getMessage("order-not-found"));
                return;
            }
            showOrderInfo(player, order);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "cancel" -> {
                PayOrder order = plugin.getOrderManager()
                        .getPlayerActiveOrder(player.getUniqueId());
                if (order == null || !order.isPending()) {
                    player.sendMessage(plugin.getXMPayConfig().getMessage("order-not-found"));
                    return;
                }
                plugin.getOrderManager().cancelOrder(order.getOutTradeNo());
                player.sendMessage(plugin.getXMPayConfig().getMessage("payment-cancelled"));
            }
            case "query" -> {
                if (args.length < 3) {
                    player.sendMessage(XMPayConfig.colorize("&c用法: /xmpay order query <订单号>"));
                    return;
                }
                String orderNo = args[2];
                // 异步查询
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    ZPayAPI.QueryResult result = plugin.getZPayAPI().queryOrder(orderNo);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (result.success) {
                            player.sendMessage(XMPayConfig.colorize("&6订单查询结果:"));
                            player.sendMessage(XMPayConfig.colorize("  &7订单号: &f" + result.outTradeNo));
                            player.sendMessage(XMPayConfig.colorize("  &7支付状态: " +
                                    (result.isPaid() ? "&a已支付" : "&e待支付")));
                            player.sendMessage(XMPayConfig.colorize("  &7金额: &f¥" + result.money));
                            player.sendMessage(XMPayConfig.colorize("  &7支付方式: &f" + result.type));
                        } else {
                            player.sendMessage(plugin.getXMPayConfig().getMessage("order-not-found"));
                        }
                    });
                });
            }
            default -> player.sendMessage(XMPayConfig.colorize("&c用法: /xmpay order [cancel|query <单号>]"));
        }
    }

    private void showTypes(Player player) {
        player.sendMessage(XMPayConfig.colorize("&6支持的支付方式:"));
        for (String type : plugin.getXMPayConfig().getEnabledPayTypes()) {
            PayOrder.PayType payType = PayOrder.PayType.fromCode(type);
            player.sendMessage(XMPayConfig.colorize("  &e" + payType.getCode()
                    + " &7- " + payType.getDisplayName()));
        }
    }

    private void showOrderInfo(Player player, PayOrder order) {
        player.sendMessage(XMPayConfig.colorize("&6当前待处理订单:"));
        player.sendMessage(XMPayConfig.colorize("  &7订单号: &f" + order.getOutTradeNo()));
        player.sendMessage(XMPayConfig.colorize("  &7金额: &f¥" + String.format("%.2f", order.getMoney())));
        player.sendMessage(XMPayConfig.colorize("  &7支付方式: &f" + order.getPayType().getDisplayName()));
        player.sendMessage(XMPayConfig.colorize("  &7状态: &e待支付"));
        player.sendMessage(XMPayConfig.colorize("  &7过期时间: &f" + order.getExpireTime()));
        if (order.getPayUrl() != null) {
            player.sendMessage(XMPayConfig.colorize("  &7支付链接: &b" + order.getPayUrl()));
        }
        player.sendMessage(XMPayConfig.colorize("  &c输入 /xmpay order cancel 可取消订单"));
    }

    /**
     * 发起支付流程（公共方法，供GUI调用）
     */
    public void initiatePayment(Player player, String productName,
                                 double amount, PayOrder.PayType payType,
                                 String packageId) {
        // 检查是否已有待处理订单
        if (plugin.getOrderManager().hasPendingOrder(player.getUniqueId())) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("already-pending"));
            return;
        }

        // 创建本地订单
        PayOrder order = plugin.getOrderManager().createOrder(
                player, productName, amount, payType, packageId);

        if (order == null) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("already-pending"));
            return;
        }

        // 执行支付前指令
        String role = getRoleForPlayer(player);
        List<String> preCmds = plugin.getXMPayConfig()
                .getRoleCommands(role, "on-payment-start");
        for (String cmd : preCmds) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    cmd.replace("{player}", player.getName()));
        }

        // 通知玩家
        int timeout = plugin.getXMPayConfig().getOrderTimeout();
        player.sendMessage(plugin.getXMPayConfig().getMessage("payment-created")
                .replace("{timeout}", String.valueOf(timeout)));

        // 异步请求支付API
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String ip = player.getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : "127.0.0.1";
            ZPayAPI.ApiResult result = plugin.getZPayAPI().createPayment(order, ip);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!result.success) {
                    player.sendMessage(plugin.getXMPayConfig().getMessage("server-error"));
                    plugin.getOrderManager().cancelOrder(order.getOutTradeNo());
                    return;
                }

                // 保存支付信息
                if (result.payUrl != null) order.setPayUrl(result.payUrl);
                if (result.qrcodeUrl != null) order.setQrcodeUrl(result.qrcodeUrl);
                if (result.qrcodeImg != null) order.setQrcodeImg(result.qrcodeImg);
                if (result.tradeNo != null) order.setTradeNo(result.tradeNo);

                // 发送支付链接
                if (result.qrcodeUrl != null || result.payUrl != null) {
                    String payLink = result.payUrl != null ? result.payUrl : result.qrcodeUrl;
                    player.sendMessage(XMPayConfig.colorize(
                            "&a支付链接（复制到浏览器打开）:"));
                    player.sendMessage(XMPayConfig.colorize("&b" + payLink));
                }

                // 生成地图二维码
                if (plugin.getXMPayConfig().isMapEnabled()) {
                    boolean mapGiven = plugin.getMapManager().giveQRCodeMap(player, order);
                    if (mapGiven) {
                        player.sendMessage(plugin.getXMPayConfig().getMessage("map-hint"));
                    }
                }
            });
        });
    }

    private String getRoleForPlayer(Player player) {
        if (player.hasPermission("xmpay.admin")) return "admin";
        if (player.hasPermission("xmpay.vip")) return "vip";
        return "default";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                       String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player)) return completions;

        if (args.length == 1) {
            List<String> subs = Arrays.asList("help", "pay", "order", "types");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "pay" -> completions.addAll(Arrays.asList("1", "5", "10", "30", "50", "100"));
                case "order" -> {
                    List<String> subs = Arrays.asList("cancel", "query");
                    for (String s : subs) {
                        if (s.startsWith(args[1].toLowerCase())) completions.add(s);
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
            for (String type : plugin.getXMPayConfig().getEnabledPayTypes()) {
                if (type.startsWith(args[2].toLowerCase())) completions.add(type);
            }
        }

        return completions;
    }
}
