package cn.xmpay.plugin.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冷却时间管理器
 * 防止玩家频繁操作
 */
public class CooldownManager {

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    /**
     * 检查是否在冷却中
     */
    public boolean isOnCooldown(UUID uuid, int cooldownSeconds) {
        Long lastTime = cooldowns.get(uuid);
        if (lastTime == null) return false;
        return (System.currentTimeMillis() - lastTime) < (cooldownSeconds * 1000L);
    }

    /**
     * 获取剩余冷却秒数
     */
    public long getRemainingSeconds(UUID uuid, int cooldownSeconds) {
        Long lastTime = cooldowns.get(uuid);
        if (lastTime == null) return 0;
        long elapsed = System.currentTimeMillis() - lastTime;
        long remaining = (cooldownSeconds * 1000L) - elapsed;
        return Math.max(0, remaining / 1000);
    }

    /**
     * 设置冷却（从现在开始）
     */
    public void setCooldown(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
    }

    /**
     * 清除冷却
     */
    public void clearCooldown(UUID uuid) {
        cooldowns.remove(uuid);
    }
}
