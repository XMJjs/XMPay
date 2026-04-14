package cn.xmpay.plugin.listener;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.model.PayOrder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

/**
 * 玩家事件监听器
 */
public class PlayerListener implements Listener {

    private final XMPayPlugin plugin;

    public PlayerListener(XMPayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家离线时处理相关逻辑
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PayOrder order = plugin.getOrderManager()
                .getPlayerActiveOrder(player.getUniqueId());

        if (order != null && order.isPending()) {
            // 保留订单，玩家重新上线可以继续
            if (plugin.getXMPayConfig().isDebug()) {
                plugin.getLogger().info("玩家 " + player.getName() +
                        " 离线，保留待处理订单: " + order.getOutTradeNo());
            }
        }
    }

    /**
     * 拦截玩家丢弃支付地图（主手强制持有的地图不可丢弃）
     */
    @EventHandler(ignoreCancelled = false)
    public void onDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemStack();
        if (item == null || item.getType() != Material.FILLED_MAP) return;

        // 检查是否是活跃订单对应的地图
        PayOrder order = plugin.getOrderManager()
                .getPlayerActiveOrder(event.getPlayer().getUniqueId());
        if (order == null || !order.isPending()) return;

        int activeMapId = order.getMapId();
        if (activeMapId < 0) return;

        // 获取被丢弃物品对应的地图ID
        int droppedMapId = -1;
        if (item.hasItemMeta()) {
            MapMeta meta = item.getItemMeta();
            if (meta.hasMapView()) {
                droppedMapId = meta.getMapView().getId();
            }
        }

        if (droppedMapId == activeMapId) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    plugin.getXMPayConfig().colorize("&c支付地图不可丢弃，请先完成支付！"));
        }
    }
}
