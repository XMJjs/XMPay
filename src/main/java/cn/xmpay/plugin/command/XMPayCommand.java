package cn.xmpay.plugin.command;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.api.ZPayAPI;
import cn.xmpay.plugin.config.XMPayConfig;
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
 *   /xmpay              - 显示帮助
 *   /xmpay help         - 显示帮助
 *   /xmpay pay <金额> [支付方式]  - 发起充值
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
            showHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> showHelp(player);
            case "pay" -> handlePay(player, args);
            case "types" -> showTypes(player);
            default -> showHelp(player);
        }

        return true;
    }

    private void showHelp(Player player) {
        String prefix = XMPayConfig.colorize("&8[&6XMPay&8] ");
        player.sendMessage(prefix + XMPayConfig.colorize("&6===== XMPay Help ====="));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay pay &b<amount> [type] &7- 发起充值"));
        player.sendMessage(prefix + XMPayConfig.colorize("&e/xmpay types &7- 查看支持的支付方式"));
        player.sendMessage(prefix + XMPayConfig.colorize("&7充值比例: &e" + plugin.getXMPayConfig().getEconomyRate()
                + " " + plugin.getXMPayConfig().getEconomyUnit() + "/元"));
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
        String productName = "游戏充值 " + String.format("%.2f", amount) + "元";
        initiatePayment(player, productName, amount, payType);
    }

    private void showTypes(Player player) {
        player.sendMessage(XMPayConfig.colorize("&6支持的支付方式:"));
        for (String type : plugin.getXMPayConfig().getEnabledPayTypes()) {
            PayOrder.PayType payType = PayOrder.PayType.fromCode(type);
            player.sendMessage(XMPayConfig.colorize("  &e" + payType.getCode()
                    + " &7- " + payType.getDisplayName()));
        }
    }

    /**
     * 发起支付流程（公共方法，供其他模块调用）
     */
    public void initiatePayment(Player player, String productName,
                                double amount, PayOrder.PayType payType) {
        // 检查是否已有待处理订单
        if (plugin.getOrderManager().hasPendingOrder(player.getUniqueId())) {
            player.sendMessage(plugin.getXMPayConfig().getMessage("already-pending"));
            return;
        }

        // 创建本地订单
        PayOrder order = plugin.getOrderManager().createOrder(
                player, productName, amount, payType);

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
                    String msg = result.message;
                    if (msg != null && msg.contains("支付插件调用失败")) {
                        player.sendMessage(XMPayConfig.colorize(
                                "&c微信支付通道未开启，请在商户后台开启微信支付后再试"));
                    } else if (msg != null && msg.contains("alipay")) {
                        player.sendMessage(XMPayConfig.colorize(
                                "&c支付宝通道未开启，请在商户后台开启支付宝后再试"));
                    } else {
                        player.sendMessage(plugin.getXMPayConfig().getMessage("server-error"));
                    }
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
                    } else {
                        // 背包已满时提示玩家清理背包后重新发起支付
                        player.sendMessage(plugin.getXMPayConfig().colorize(
                                "&c背包已满！请清理背包后重新发起支付 (/xmpay pay "
                                + String.format("%.2f", amount) + ")"));
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
            List<String> subs = Arrays.asList("help", "pay", "types");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "pay" -> completions.addAll(Arrays.asList("1", "5", "10", "30", "50", "100"));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
            for (String type : plugin.getXMPayConfig().getEnabledPayTypes()) {
                if (type.startsWith(args[2].toLowerCase())) completions.add(type);
            }
        }

        return completions;
    }
}
