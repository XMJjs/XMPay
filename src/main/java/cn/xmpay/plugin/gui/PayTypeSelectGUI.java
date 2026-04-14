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

import java.util.ArrayList;
import java.util.List;

/**
 * 支付方式选择界面
 * 在玩家选择套餐后，展示可用的支付方式（支付宝/微信等）
 */
public class PayTypeSelectGUI implements Listener {

    private final XMPayPlugin plugin;
    private final Player player;
    private final XMPayConfig.PaymentPackage pkg;
    private final Inventory inventory;

    private static final String TITLE = XMPayConfig.colorize("&6选择支付方式");

    public PayTypeSelectGUI(XMPayPlugin plugin, Player player,
                             XMPayConfig.PaymentPackage pkg) {
        this.plugin = plugin;
        this.player = player;
        this.pkg = pkg;
        this.inventory = Bukkit.createInventory(null, 27, TITLE);
        buildInventory();
    }

    private void buildInventory() {
        // 背景
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inventory.setItem(i, filler);

        List<String> enabledTypes = plugin.getXMPayConfig().getEnabledPayTypes();
        int startSlot = (9 - enabledTypes.size() * 2 + 1) / 2 + 9;

        for (int i = 0; i < enabledTypes.size(); i++) {
            String typeCode = enabledTypes.get(i);
            PayOrder.PayType payType = PayOrder.PayType.fromCode(typeCode);

            Material icon = switch (typeCode.toLowerCase()) {
                case "alipay" -> Material.LIGHT_BLUE_DYE;
                case "wxpay" -> Material.LIME_DYE;
                case "qqpay" -> Material.BLUE_DYE;
                default -> Material.PAPER;
            };

            List<String> lore = new ArrayList<>();
            lore.add(XMPayConfig.colorize("&7套餐: &f" + pkg.name));
            lore.add(XMPayConfig.colorize("&7金额: &f¥" + String.format("%.2f", pkg.amount)));
            lore.add("");
            lore.add(XMPayConfig.colorize("&e点击使用 " + payType.getDisplayName() + " 支付"));

            int slot = startSlot + i * 2;
            inventory.setItem(slot, createItem(icon,
                    XMPayConfig.colorize("&f" + payType.getDisplayName()), lore));
        }

        // 返回按钮
        inventory.setItem(18, createItem(Material.ARROW,
                XMPayConfig.colorize("&7← 返回"),
                List.of(XMPayConfig.colorize("&7返回套餐选择"))));

        // 取消按钮
        inventory.setItem(26, createItem(Material.BARRIER,
                XMPayConfig.colorize("&c取消"), List.of()));
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
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        // 返回按钮
        if (slot == 18) {
            player.closeInventory();
            new PaymentGUI(plugin, player).open();
            return;
        }

        // 取消
        if (slot == 26) {
            player.closeInventory();
            return;
        }

        // 支付方式选择
        List<String> enabledTypes = plugin.getXMPayConfig().getEnabledPayTypes();
        int startSlot = (9 - enabledTypes.size() * 2 + 1) / 2 + 9;

        for (int i = 0; i < enabledTypes.size(); i++) {
            if (slot == startSlot + i * 2) {
                String typeCode = enabledTypes.get(i);
                PayOrder.PayType payType = PayOrder.PayType.fromCode(typeCode);
                player.closeInventory();

                XMPayCommand cmd = (XMPayCommand) plugin.getCommand("xmpay").getExecutor();
                cmd.initiatePayment(player, pkg.name, pkg.amount, payType, pkg.id);
                return;
            }
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
