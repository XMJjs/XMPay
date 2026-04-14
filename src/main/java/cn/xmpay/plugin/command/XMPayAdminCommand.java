package cn.xmpay.plugin.command;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.api.ZPayAPI;
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
 *   /xmpayadmin rate <汇率>         - 设置兑换汇率（临时，重启失效）
 *   /xmpayadmin check               - 检测支付服务配置状态
 *   /xmpayadmin test                - 测试支付API连通性
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
            case "check" -> handleCheck(sender);
            case "test" -> handleTest(sender);
            case "orders" -> handleOrders(sender);
            case "rate" -> handleRate(sender, args);
            case "pay" -> handleAdminPay(sender, args);
            default -> showAdminHelp(sender);
        }

        return true;
    }

    private void showAdminHelp(CommandSender sender) {
        sender.sendMessage(XMPayConfig.colorize("&8===== &6XMPay Admin &8====="));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin reload &7- Reload config"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin info &7- Plugin status"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin orders &7- View active orders"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin rate &b<rate> &7- Set economy rate (temp)"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin check &7- Check payment service config"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin test &7- Test payment API connectivity"));
        sender.sendMessage(XMPayConfig.colorize("&e/xmpayadmin pay &b<player> <amount> [type] &7- Initiate payment for player"));
    }

    private void handleReload(CommandSender sender) {
        try {
            plugin.reload();
            sender.sendMessage(plugin.getXMPayConfig().getMessage("reload-success"));
        } catch (Exception e) {
            sender.sendMessage(XMPayConfig.colorize("&cReload failed: " + e.getMessage()));
        }
    }

    private void handleCheck(CommandSender sender) {
        sender.sendMessage(XMPayConfig.colorize("&8===== &6Payment Service Check &8====="));
        ZPayAPI.ServiceCheckResult result = plugin.getZPayAPI().checkServiceAvailability();
        if (result.available) {
            sender.sendMessage(XMPayConfig.colorize("&aOK - Payment service available"));
        } else {
            sender.sendMessage(XMPayConfig.colorize("&cFAIL - Payment service unavailable"));
            sender.sendMessage(XMPayConfig.colorize("&eReason: &f" + result.message));
        }
        sender.sendMessage(XMPayConfig.colorize("&7Merchant ID: &f" + plugin.getXMPayConfig().getPid()));
        sender.sendMessage(XMPayConfig.colorize("&7API URL: &f" + plugin.getXMPayConfig().getApiUrl()));
        sender.sendMessage(XMPayConfig.colorize("&7Notify URL: &f" + plugin.getXMPayConfig().buildNotifyUrl()));
        sender.sendMessage(XMPayConfig.colorize("&7Debug mode: &f" + plugin.getXMPayConfig().isDebug()));
    }

    private void handleTest(CommandSender sender) {
        sender.sendMessage(XMPayConfig.colorize("&eTesting payment API connectivity..."));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ZPayAPI.ApiResult result = plugin.getZPayAPI().testConnection();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.success) {
                    sender.sendMessage(XMPayConfig.colorize("&aOK - API connectivity normal"));
                    if (result.payUrl != null) {
                        sender.sendMessage(XMPayConfig.colorize("&7Pay URL: &f" + result.payUrl));
                    }
                } else {
                    sender.sendMessage(XMPayConfig.colorize("&cFAIL - API connectivity failed"));
                    sender.sendMessage(XMPayConfig.colorize("&eError: &f" + result.message));
                    sender.sendMessage(XMPayConfig.colorize("&7Check: Merchant ID / Key / API URL"));
                }
            });
        });
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(XMPayConfig.colorize("&8===== &6XMPay Status &8====="));
        sender.sendMessage(XMPayConfig.colorize("&7Version: &f" + plugin.getDescription().getVersion()));
        sender.sendMessage(XMPayConfig.colorize("&7Merchant ID: &f" + plugin.getXMPayConfig().getPid()));
        sender.sendMessage(XMPayConfig.colorize("&7API URL: &f" + plugin.getXMPayConfig().getApiUrl()));
        sender.sendMessage(XMPayConfig.colorize("&7Active Orders: &f" +
                plugin.getOrderManager().getAllActiveOrders().size()));
        sender.sendMessage(XMPayConfig.colorize("&7Economy: &f" +
                (plugin.getEconomyManager() != null
                        ? plugin.getEconomyManager().getActiveType()
                        : "Not loaded")));
        sender.sendMessage(XMPayConfig.colorize("&7Rate: &f1 CNY = " +
                plugin.getXMPayConfig().getEconomyRate() + " " +
                (plugin.getEconomyManager() != null
                        ? plugin.getEconomyManager().getCurrencyName() : "currency")));
        sender.sendMessage(XMPayConfig.colorize("&7HTTP Callback: &f" +
                (plugin.getCallbackServer() != null ? "Running " : "Not started ") +
                plugin.getXMPayConfig().buildNotifyUrl()));
        sender.sendMessage(XMPayConfig.colorize("&7Enabled Types: &f" +
                String.join(", ", plugin.getXMPayConfig().getEnabledPayTypes())));
        sender.sendMessage(XMPayConfig.colorize("&7Debug: &f" +
                plugin.getXMPayConfig().isDebug()));
    }

    private void handleOrders(CommandSender sender) {
        Collection<PayOrder> orders = plugin.getOrderManager().getAllActiveOrders();
        if (orders.isEmpty()) {
            sender.sendMessage(XMPayConfig.colorize("&7No active orders."));
            return;
        }
        sender.sendMessage(XMPayConfig.colorize("&6Active Orders (" + orders.size() + "):"));
        for (PayOrder order : orders) {
            sender.sendMessage(XMPayConfig.colorize(
                    "  &e" + order.getOutTradeNo() +
                    " &7| Player: &f" + order.getPlayerName() +
                    " &7| Amount: &f" + String.format("%.2f", order.getMoney()) +
                    " &7| Status: " + (order.isPending() ? "&ePending" : "&aPaid")));
        }
    }

    private void handleRate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            double current = plugin.getXMPayConfig().getEconomyRate();
            sender.sendMessage(XMPayConfig.colorize("&7Current rate: &f1 CNY = " + current + " currency"));
            sender.sendMessage(XMPayConfig.colorize("&cThis is a temporary setting. Edit config.yml for permanent change."));
            return;
        }
        try {
            double rate = Double.parseDouble(args[1]);
            plugin.getConfig().set("economy.rate", rate);
            sender.sendMessage(XMPayConfig.colorize("&aRate set to: 1 CNY = " + rate + " currency"));
            sender.sendMessage(XMPayConfig.colorize("&7Edit config.yml and run /xmpayadmin reload for permanent change."));
        } catch (NumberFormatException e) {
            sender.sendMessage(XMPayConfig.colorize("&cInvalid rate value!"));
        }
    }

    private void handleAdminPay(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(XMPayConfig.colorize("&cUsage: /xmpayadmin pay <player> <amount> [alipay|wxpay]"));
            return;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(XMPayConfig.colorize("&cPlayer " + args[1] + " is not online!"));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(XMPayConfig.colorize("&cInvalid amount!"));
            return;
        }

        String typeStr = args.length >= 4 ? args[3]
                : plugin.getXMPayConfig().getDefaultPayType();
        PayOrder.PayType payType = PayOrder.PayType.fromCode(typeStr);

        sender.sendMessage(XMPayConfig.colorize("&aInitiated payment for &e" + target.getName() +
                " &a- Amount: " + String.format("%.2f", amount)));

        XMPayCommand cmd = (XMPayCommand) plugin.getCommand("xmpay").getExecutor();
        cmd.initiatePayment(target, "Admin recharge " + String.format("%.2f", amount),
                amount, payType, null);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                       String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("xmpay.admin")) return completions;

        if (args.length == 1) {
            List<String> subs = Arrays.asList("reload", "info", "check", "test", "orders",
                    "rate", "pay");
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
