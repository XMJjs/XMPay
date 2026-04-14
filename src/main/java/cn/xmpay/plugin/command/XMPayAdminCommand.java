package cn.xmpay.plugin.command;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.config.XMPayConfig;
import cn.xmpay.plugin.model.PayOrder;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * XMPay 管理员指令处理器
 *
 * 指令列表:
 *   /xmpayadmin reload              - 重载配置
 *   /xmpayadmin info                - 查看插件状态
 *   /xmpayadmin orders              - 查看所有活跃订单
 *   /xmpayadmin order <单号>        - 查看指定订单详情
 *   /xmpayadmin cancel <单号>       - 取消指定订单
 *   /xmpayadmin refund <单号>       - 退款指定订单
 *   /xmpayadmin toggle <类型>       - 开关支付方式
 *   /xmpayadmin rate <汇率>         - 设置兑换汇率（临时，重启失效）
 *   /xmpayadmin pay <玩家> <金额> [类型] - 为玩家发起支付
 */
public class XMPayAdminCommand implements CommandExecutor, TabCompleter {

    private final XMPayPlugin plugin;

    public XMPayAdminCommand(XMPayPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                              String label, String[] args) {
        if (!sender.hasPermission("xmpay.admin")) {
            sender.sendMessage(plugin.getXMPayConfig().getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            showAdminHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "info" -> handleInfo(sender);
            case "orders" -> handleOrders(sender);
            case "order" -> handleOrderDetail(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "refund" -> handleRefund(sender, args);
            case "rate" -> handleRate(sender, args);
            case "pay" -> handleAdminPay(sender, args);
            default -> showAdminHelp(sender);
        }

        return true;
    }

    private void showAdminHelp(CommandSender sender) {
        sender.sendMessage(XMPayConfig.colorize("&8===== &6XMPay Admin &8====="));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin reload &7- 重载配置"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin info &7- 插件状态"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin orders &7- 查看活跃订单"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin order &b<单号> &7- 订单详情"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin cancel &b<单号> &7- 取消订单"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin refund &b<单号> &7- 申请退款"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin rate &b<汇率> &7- 设置兑换率（临时）"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin pay &b<玩家> <金额> [类型] &7- 代发支付"));
    }

    private void handleReload(CommandSender sender) {
        try {
            plugin.reload();
            sender.sendMessage(plugin.getXMPayConfig().getMessage("reload-success"));
        } catch (Exception e) {
            sender.sendMessage(XMPayConfig.colorize("&c重载失败: " + e.getMessage()));
        }
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(XMPayConfig.colorize("&8===== &6XMPay 状态信息 &8====="));
        sender.sendMessage(XMPayConfig.colorize("&7版本: &f" + plugin.getDescription().getVersion()));
        sender.sendMessage(XMPayConfig.colorize("&7商户ID: &f" + plugin.getXMPayConfig().getPid()));
        sender.sendMessage(XMPayConfig.colorize("&7支付平台: &f" + plugin.getXMPayConfig().getApiUrl()));
        sender.sendMessage(XMPayConfig.colorize("&7活跃订单: &f" +
                plugin.getOrderManager().getAllActiveOrders().size()));
        sender.sendMessage(XMPayConfig.colorize("&7经济系统: &f" +
                (plugin.getEconomyManager() != null
                        ? plugin.getEconomyManager().getActiveType()
                        : "未加载")));
        sender.sendMessage(XMPayConfig.colorize("&7汇率: &f1元 = " +
                plugin.getXMPayConfig().getEconomyRate() + " " +
                (plugin.getEconomyManager() != null
                        ? plugin.getEconomyManager().getCurrencyName() : "货币")));
        sender.sendMessage(XMPayConfig.colorize("&7HTTP回调: &f" +
                (plugin.getCallbackServer() != null ? "运行中 " : "未启动 ") +
                plugin.getXMPayConfig().buildNotifyUrl()));
        sender.sendMessage(XMPayConfig.colorize("&7已启用支付方式: &f" +
                String.join(", ", plugin.getXMPayConfig().getEnabledPayTypes())));
        sender.sendMessage(XMPayConfig.colorize("&7调试模式: &f" +
                plugin.getXMPayConfig().isDebug()));
    }

    private void handleOrders(CommandSender sender) {
        Collection<PayOrder> orders = plugin.getOrderManager().getAllActiveOrders();
        if (orders.isEmpty()) {
            sender.sendMessage(XMPayConfig.colorize("&7当前无活跃订单。"));
            return;
        }
        sender.sendMessage(XMPayConfig.colorize("&6活跃订单列表 (" + orders.size() + "):"));
        for (PayOrder order : orders) {
            sender.sendMessage(XMPayConfig.colorize(
                    "  &e" + order.getOutTradeNo() +
                    " &7| 玩家: &f" + order.getPlayerName() +
                    " &7| 金额: &f¥" + String.format("%.2f", order.getMoney()) +
                    " &7| 状态: " + (order.isPending() ? "&e待支付" : "&a已支付")));
        }
    }

    private void handleOrderDetail(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(XMPayConfig.colorize("&c用法: /xmpayadmin order <订单号>"));
            return;
        }
        PayOrder order = plugin.getOrderManager().getOrder(args[1]);
        if (order == null) {
            sender.sendMessage(plugin.getXMPayConfig().getMessage("order-not-found"));
            return;
        }
        sender.sendMessage(XMPayConfig.colorize("&8===== &6订单详情 &8====="));
        sender.sendMessage(XMPayConfig.colorize("&7商户单号: &f" + order.getOutTradeNo()));
        sender.sendMessage(XMPayConfig.colorize("&7平台单号: &f" +
                (order.getTradeNo() != null ? order.getTradeNo() : "未获取")));
        sender.sendMessage(XMPayConfig.colorize("&7玩家: &f" + order.getPlayerName() +
                " (" + order.getPlayerUUID() + ")"));
        sender.sendMessage(XMPayConfig.colorize("&7商品: &f" + order.getProductName()));
        sender.sendMessage(XMPayConfig.colorize("&7金额: &f¥" +
                String.format("%.2f", order.getMoney())));
        sender.sendMessage(XMPayConfig.colorize("&7支付方式: &f" +
                order.getPayType().getDisplayName()));
        sender.sendMessage(XMPayConfig.colorize("&7状态: &f" + order.getStatus()));
        sender.sendMessage(XMPayConfig.colorize("&7创建时间: &f" + order.getCreateTime()));
        sender.sendMessage(XMPayConfig.colorize("&7过期时间: &f" + order.getExpireTime()));
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(XMPayConfig.colorize("&c用法: /xmpayadmin cancel <订单号>"));
            return;
        }
        PayOrder order = plugin.getOrderManager().getOrder(args[1]);
        if (order == null) {
            sender.sendMessage(plugin.getXMPayConfig().getMessage("order-not-found"));
            return;
        }
        plugin.getOrderManager().cancelOrder(args[1]);
        sender.sendMessage(XMPayConfig.colorize("&a订单 " + args[1] + " 已取消。"));
    }

    private void handleRefund(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xmpay.order.refund")) {
            sender.sendMessage(plugin.getXMPayConfig().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(XMPayConfig.colorize("&c用法: /xmpayadmin refund <订单号>"));
            return;
        }
        PayOrder order = plugin.getOrderManager().getOrder(args[1]);
        String money;
        if (order != null) {
            money = String.format("%.2f", order.getMoney());
        } else {
            if (args.length < 3) {
                sender.sendMessage(XMPayConfig.colorize("&c历史订单请指定金额: /xmpayadmin refund <单号> <金额>"));
                return;
            }
            money = args[2];
        }

        final String finalMoney = money;
        sender.sendMessage(XMPayConfig.colorize("&e正在提交退款申请..."));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            cn.xmpay.plugin.api.ZPayAPI.ApiResult result =
                    plugin.getZPayAPI().refundOrder(args[1], finalMoney);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.success) {
                    sender.sendMessage(XMPayConfig.colorize("&a退款申请成功！"));
                } else {
                    sender.sendMessage(XMPayConfig.colorize("&c退款失败: " + result.message));
                }
            });
        });
    }

    private void handleRate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            double current = plugin.getXMPayConfig().getEconomyRate();
            sender.sendMessage(XMPayConfig.colorize("&7当前汇率: &f1元 = " + current + " 货币"));
            sender.sendMessage(XMPayConfig.colorize("&c提示：此命令为临时设置，重启后恢复。请修改 config.yml 永久生效。"));
            return;
        }
        try {
            double rate = Double.parseDouble(args[1]);
            // 临时修改配置（不保存到文件）
            plugin.getConfig().set("economy.rate", rate);
            sender.sendMessage(XMPayConfig.colorize("&a汇率已临时设置为: 1元 = " + rate + " 货币"));
            sender.sendMessage(XMPayConfig.colorize("&7永久修改请编辑 config.yml 并执行 /xmpayadmin reload"));
        } catch (NumberFormatException e) {
            sender.sendMessage(XMPayConfig.colorize("&c无效的汇率值！"));
        }
    }

    private void handleAdminPay(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(XMPayConfig.colorize("&c用法: /xmpayadmin pay <玩家> <金额> [alipay|wxpay]"));
            return;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(XMPayConfig.colorize("&c玩家 " + args[1] + " 不在线！"));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(XMPayConfig.colorize("&c无效金额！"));
            return;
        }

        String typeStr = args.length >= 4 ? args[3]
                : plugin.getXMPayConfig().getDefaultPayType();
        PayOrder.PayType payType = PayOrder.PayType.fromCode(typeStr);

        sender.sendMessage(XMPayConfig.colorize("&a已为玩家 &e" + target.getName() +
                " &a发起支付请求 ¥" + String.format("%.2f", amount)));

        // 复用玩家指令的支付逻辑
        XMPayCommand cmd = (XMPayCommand) plugin.getCommand("xmpay").getExecutor();
        cmd.initiatePayment(target, "管理员充值 ¥" + String.format("%.2f", amount),
                amount, payType, null);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                       String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("xmpay.admin")) return completions;

        if (args.length == 1) {
            List<String> subs = Arrays.asList("reload", "info", "orders", "order",
                    "cancel", "refund", "rate", "pay");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pay")) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(p.getName());
                }
            }
        }

        return completions;
    }
}
