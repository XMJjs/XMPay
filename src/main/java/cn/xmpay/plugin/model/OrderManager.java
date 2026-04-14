package cn.xmpay.plugin.model;

import cn.xmpay.plugin.XMPayPlugin;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 订单管理器
 * 负责订单的创建、查询、超时处理
 */
public class OrderManager {

    private final XMPayPlugin plugin;

    // 内存中的活跃订单：outTradeNo -> PayOrder
    private final Map<String, PayOrder> activeOrders = new ConcurrentHashMap<>();

    // 玩家当前活跃订单：playerUUID -> outTradeNo
    private final Map<UUID, String> playerActiveOrder = new ConcurrentHashMap<>();

    // 每日订单计数：playerUUID -> count（简单实现，重启重置）
    private final Map<UUID, Integer> dailyOrderCount = new ConcurrentHashMap<>();

    // 历史完成订单（保留最近100条）
    private final LinkedList<PayOrder> completedOrders = new LinkedList<>();
    private static final int MAX_HISTORY = 100;

    public OrderManager(XMPayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 创建新订单
     *
     * @return 创建的订单，如果玩家已有待处理订单则返回null
     */
    public PayOrder createOrder(Player player, String productName,
                                 double money, PayOrder.PayType payType,
                                 String packageId) {
        UUID uuid = player.getUniqueId();

        // 检查玩家是否已有待处理订单
        if (hasPendingOrder(uuid)) {
            return null;
        }

        // 检查每日限额
        int maxDaily = plugin.getXMPayConfig().getMaxDailyOrders();
        if (maxDaily > 0) {
            int count = dailyOrderCount.getOrDefault(uuid, 0);
            if (count >= maxDaily) {
                return null;
            }
        }

        int timeout = plugin.getXMPayConfig().getOrderTimeout();
        PayOrder order = new PayOrder(uuid, player.getName(), productName,
                money, payType, packageId, timeout);

        activeOrders.put(order.getOutTradeNo(), order);
        playerActiveOrder.put(uuid, order.getOutTradeNo());

        plugin.getLogger().info("新订单创建: " + order);
        return order;
    }

    /**
     * 根据商户订单号获取订单
     */
    public PayOrder getOrder(String outTradeNo) {
        return activeOrders.get(outTradeNo);
    }

    /**
     * 获取玩家当前活跃订单
     */
    public PayOrder getPlayerActiveOrder(UUID playerUUID) {
        String orderNo = playerActiveOrder.get(playerUUID);
        if (orderNo == null) return null;
        return activeOrders.get(orderNo);
    }

    /**
     * 检查玩家是否有待处理订单
     */
    public boolean hasPendingOrder(UUID playerUUID) {
        PayOrder order = getPlayerActiveOrder(playerUUID);
        return order != null && order.isPending() && !order.isExpired();
    }

    /**
     * 取消订单
     */
    public void cancelOrder(String outTradeNo) {
        PayOrder order = activeOrders.get(outTradeNo);
        if (order != null) {
            order.setStatus(PayOrder.Status.CANCELLED);
            order.setFinishTime(LocalDateTime.now());
            removeFromActive(order);
        }
    }

    /**
     * 标记订单为已支付（由回调触发）
     */
    public void markPaid(String outTradeNo, String tradeNo) {
        PayOrder order = activeOrders.get(outTradeNo);
        if (order == null) {
            // 尝试在历史中查找（防止重复通知）
            plugin.getLogger().info("收到支付回调但订单不在活跃列表: " + outTradeNo);
            return;
        }

        if (order.isPaid()) {
            plugin.getLogger().info("订单已支付，忽略重复通知: " + outTradeNo);
            return;
        }

        order.setTradeNo(tradeNo);
        order.setStatus(PayOrder.Status.PAID);
        order.setFinishTime(LocalDateTime.now());

        // 增加每日计数
        dailyOrderCount.merge(order.getPlayerUUID(), 1, Integer::sum);

        // 从活跃订单移除，加入历史
        removeFromActive(order);
        addToHistory(order);

        plugin.getLogger().info("订单支付成功: " + outTradeNo + " 玩家: " + order.getPlayerName());
    }

    /**
     * 超时检查（定时任务调用）
     */
    public void checkTimeouts() {
        List<String> toTimeout = new ArrayList<>();

        for (PayOrder order : activeOrders.values()) {
            if (order.isPending() && order.isExpired()) {
                toTimeout.add(order.getOutTradeNo());
            }
        }

        for (String orderNo : toTimeout) {
            PayOrder order = activeOrders.get(orderNo);
            if (order != null) {
                order.setStatus(PayOrder.Status.TIMEOUT);
                order.setFinishTime(LocalDateTime.now());
                removeFromActive(order);
                addToHistory(order);

                // 通知玩家并移除背包中的支付地图（主线程执行）
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    org.bukkit.entity.Player p = plugin.getServer()
                            .getPlayer(order.getPlayerUUID());
                    if (p != null && p.isOnline()) {
                        p.sendMessage(plugin.getXMPayConfig().getMessage("payment-timeout"));
                        removeMapFromPlayer(p, order.getMapId());
                    }
                });

                if (plugin.getXMPayConfig().isDebug()) {
                    plugin.getLogger().info("订单超时: " + orderNo);
                }
            }
        }
    }

    /**
     * 根据outTradeNo从历史中查找（防重放）
     */
    public boolean isOrderInHistory(String outTradeNo) {
        return completedOrders.stream()
                .anyMatch(o -> o.getOutTradeNo().equals(outTradeNo));
    }

    /**
     * 获取所有活跃订单（管理员用）
     */
    public Collection<PayOrder> getAllActiveOrders() {
        return Collections.unmodifiableCollection(activeOrders.values());
    }

    /**
     * 获取历史订单
     */
    public List<PayOrder> getCompletedOrders() {
        return Collections.unmodifiableList(completedOrders);
    }

    /**
     * 插件关闭时保存数据
     */
    public void saveAll() {
        // 当前版本使用内存存储，后续可扩展为数据库
        plugin.getLogger().info("活跃订单数: " + activeOrders.size());
    }

    private void removeFromActive(PayOrder order) {
        activeOrders.remove(order.getOutTradeNo());
        playerActiveOrder.remove(order.getPlayerUUID());
    }

    /**
     * 从玩家背包中移除指定ID的支付地图
     */
    public void removeMapFromPlayer(org.bukkit.entity.Player player, int mapId) {
        if (mapId < 0) return;
        org.bukkit.Material mapType = org.bukkit.Material.FILLED_MAP;
        org.bukkit.inventory.ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            org.bukkit.inventory.ItemStack item = contents[i];
            if (item != null && item.getType() == mapType) {
                org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
                if (meta.hasMapView() && meta.getMapView().getId() == mapId) {
                    player.getInventory().setItem(i, null);
                    if (plugin.getXMPayConfig().isDebug()) {
                        plugin.getLogger().info("已从玩家背包移除过期支付地图: " + mapId);
                    }
                    return;
                }
            }
        }
    }

    private void addToHistory(PayOrder order) {
        completedOrders.addFirst(order);
        while (completedOrders.size() > MAX_HISTORY) {
            completedOrders.removeLast();
        }
    }
}
