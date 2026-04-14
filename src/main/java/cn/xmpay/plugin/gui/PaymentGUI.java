package cn.xmpay.plugin.gui;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.command.XMPayCommand;
import cn.xmpay.plugin.config.XMPayConfig;
import cn.xmpay.plugin.model.PayOrder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * 充值套餐选择GUI
 * 以箱子界面展示可购买的充值套餐
 */
public class PaymentGUI implements Listener {

    private final XMPayPlugin plugin;
    private final Player player;
    private final Inventory inventory;
    private final Map<Integer, XMPayConfig.PaymentPackage> slotToPackage = new HashMap<>();

    // GUI标题（纯ASCII，避免地图字体渲染崩溃）
    private static final String TITLE = XMPayConfig.colorize("&6[&eXMPay&6] &eRecharge Center");

    public PaymentGUI(XMPayPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(null, 54, TITLE);
        buildInventory();
    }

    private void buildInventory() {
        // 填充背景格（灰色玻璃板）
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // 放置套餐图标
        List<XMPayConfig.PaymentPackage> packages = plugin.getXMPayConfig().getPackages();
        for (XMPayConfig.PaymentPackage pkg : packages) {
            // 检查权限
            if (pkg.requiredPermission != null && !pkg.requiredPermission.isEmpty()
                    && !player.hasPermission(pkg.requiredPermission)) {
                // 显示为锁定状态
                ItemStack locked = createItem(Material.BARRIER,
                        XMPayConfig.colorize("&c" + pkg.name),
                        List.of(XMPayConfig.colorize("&7需要权限: &c" + pkg.requiredPermission)));
                inventory.setItem(pkg.slot, locked);
                continue;
            }

            Material icon;
            try {
                icon = Material.valueOf(pkg.icon.toUpperCase());
            } catch (IllegalArgumentException e) {
                icon = Material.GOLD_INGOT;
            }

            List<String> lore = new ArrayList<>();
            lore.add(XMPayConfig.colorize("&7金额: &f¥" + String.format("%.2f", pkg.amount)));
            if (pkg.bonus > 0) {
                lore.add(XMPayConfig.colorize("&a赠送: &f+" + pkg.bonus));
            }
            double gameAmount = pkg.amount * plugin.getXMPayConfig().getEconomyRate();
            lore.add(XMPayConfig.colorize("&7到账: &e" + (int) gameAmount +
                    (plugin.getEconomyManager() != null
                            ? " " + plugin.getEconomyManager().getCurrencyName() : " 货币")));
            lore.add("");
            lore.add(XMPayConfig.colorize("&e点击选择支付方式"));

            ItemStack pkgItem = createItem(icon, XMPayConfig.colorize(pkg.name), lore);
            inventory.setItem(pkg.slot, pkgItem);
            slotToPackage.put(pkg.slot, pkg);
        }

        // 底部导航栏
        inventory.setItem(45, createItem(Material.PAPER,
                XMPayConfig.colorize("&e自定义金额"),
                List.of(XMPayConfig.colorize("&7使用 &e/xmpay pay <金额> &7命令"))));

        inventory.setItem(49, createItem(Material.BOOK,
                XMPayConfig.colorize("&b当前订单"),
                List.of(XMPayConfig.colorize("&7使用 &b/xmpay order &7查看"))));

        inventory.setItem(53, createItem(Material.BARRIER,
                XMPayConfig.colorize("&c关闭"), List.of()));
    }

    public void open() {
        player.openInventory(inventory);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player clicker)) return;
        if (!clicker.equals(player)) return;
        if (!event.getView().getTitle().equals(TITLE)) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();

        // 关闭按钮
        if (slot == 53) {
            player.closeInventory();
            return;
        }

        // 套餐选择
        XMPayConfig.PaymentPackage pkg = slotToPackage.get(slot);
        if (pkg == null) return;

        player.closeInventory();

        // 如果只启用了一种支付方式，直接发起
        List<String> enabledTypes = plugin.getXMPayConfig().getEnabledPayTypes();
        if (enabledTypes.size() == 1) {
            PayOrder.PayType payType = PayOrder.PayType.fromCode(enabledTypes.get(0));
            XMPayCommand cmd = (XMPayCommand) plugin.getCommand("xmpay").getExecutor();
            cmd.initiatePayment(player, pkg.name, pkg.amount, payType, pkg.id);
        } else {
            // 打开支付方式选择界面
            new PayTypeSelectGUI(plugin, player, pkg).open();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer().equals(player)) {
            HandlerList.unregisterAll(this);
        }
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem(Material material, String name) {
        return createItem(material, name, new ArrayList<>());
    }
}
