package cn.xmpay.plugin.config;

import cn.xmpay.plugin.XMPayPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件管理器
 * 封装所有配置项的读取，提供类型安全的访问接口
 */
public class XMPayConfig {

    private final XMPayPlugin plugin;
    private FileConfiguration config;

    public XMPayConfig(XMPayPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    public void reload() {
        this.config = plugin.getConfig();
    }

    // ===== 易支付平台配置 =====

    public String getApiUrl() {
        return config.getString("payment.api-url", "https://zpayz.cn");
    }

    public String getPid() {
        return config.getString("payment.pid", "");
    }

    public String getKey() {
        return config.getString("payment.key", "");
    }

    public String getDefaultPayType() {
        return config.getString("payment.default-type", "alipay");
    }

    public List<String> getEnabledPayTypes() {
        return config.getStringList("payment.enabled-types");
    }

    public String getCid() {
        return config.getString("payment.cid", "");
    }

    public int getOrderTimeout() {
        return config.getInt("payment.order-timeout", 30);
    }

    public String getNotifyUrl() {
        return config.getString("payment.notify-url", "");
    }

    public String getReturnUrl() {
        return config.getString("payment.return-url", "");
    }

    // ===== HTTP服务器配置 =====

    public boolean isHttpServerEnabled() {
        return config.getBoolean("http-server.enabled", true);
    }

    public int getHttpServerPort() {
        return config.getInt("http-server.port", 25566);
    }

    public String getHttpServerBind() {
        return config.getString("http-server.bind", "0.0.0.0");
    }

    public String getCallbackPath() {
        return config.getString("http-server.callback-path", "/xmpay/notify");
    }

    public String getPublicHost() {
        return config.getString("http-server.public-host", "");
    }

    /**
     * 获取完整的回调通知URL
     */
    public String buildNotifyUrl() {
        String custom = getNotifyUrl();
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        String host = getPublicHost();
        if (host != null && !host.isEmpty()) {
            return "http://" + host + ":" + getHttpServerPort() + getCallbackPath();
        }
        return "http://127.0.0.1:" + getHttpServerPort() + getCallbackPath();
    }

    // ===== 经济系统配置 =====

    public String getEconomyPrimary() {
        return config.getString("economy.primary", "vault");
    }

    public double getEconomyRate() {
        return config.getDouble("economy.rate", 100.0);
    }

    public boolean isFeeEnabled() {
        return config.getBoolean("economy.fee-enabled", false);
    }

    public double getFeeRate() {
        return config.getDouble("economy.fee-rate", 0.0);
    }

    public double getMinAmount() {
        return config.getDouble("economy.min-amount", 0.01);
    }

    public double getMaxAmount() {
        return config.getDouble("economy.max-amount", 9999.00);
    }

    public int getPlayerPointsRate() {
        return config.getInt("economy.playerpoints.rate", 100);
    }

    public String getEconomyUnit() {
        return config.getString("economy.unit", "金币");
    }

    // ===== 地图配置 =====

    public boolean isMapEnabled() {
        return config.getBoolean("map.enabled", true);
    }

    public int getMapDisplayDuration() {
        return config.getInt("map.display-duration", 120);
    }

    public boolean isMapShowAmount() {
        return config.getBoolean("map.show-amount", true);
    }

    public boolean isMapShowTypeIcon() {
        return config.getBoolean("map.show-type-icon", true);
    }

    // ===== 身份权限指令配置 =====

    public List<String> getRoleCommands(String role, String event) {
        String path = "roles." + role + "." + event;
        return config.getStringList(path);
    }

    // ===== 消息配置 =====

    public String getMessage(String key) {
        String prefix = config.getString("messages.prefix", "&8[&6XMPay&8] &r");
        String msg = config.getString("messages." + key, "");
        return colorize(prefix + msg);
    }

    public String getMessageRaw(String key) {
        return config.getString("messages." + key, "");
    }

    // ===== 安全配置 =====

    public int getCooldown() {
        return config.getInt("security.cooldown", 5);
    }

    public int getMaxDailyOrders() {
        return config.getInt("security.max-daily-orders", 20);
    }

    public boolean isVerifySign() {
        return config.getBoolean("security.verify-sign", true);
    }

    public List<String> getNotifyIpWhitelist() {
        return config.getStringList("security.notify-ip-whitelist");
    }

    // ===== 调试配置 =====

    public boolean isDebug() {
        return config.getBoolean("debug", false);
    }

    // ===== 工具方法 =====

    public static String colorize(String text) {
        if (text == null) return "";
        return text.replace("&", "\u00A7");
    }
}
