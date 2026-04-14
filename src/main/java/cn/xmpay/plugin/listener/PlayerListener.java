package cn.xmpay.plugin.listener;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.model.PayOrder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

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
}
