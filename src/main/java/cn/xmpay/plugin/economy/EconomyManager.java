package cn.xmpay.plugin.economy;

import cn.xmpay.plugin.XMPayPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.logging.Level;

/**
 * 经济系统管理器
 * 支持 Vault（对接大多数经济插件）和 PlayerPoints（点券）
 * 优先使用配置的主要经济源
 */
public class EconomyManager {

    private final XMPayPlugin plugin;
    private Economy vaultEconomy;
    private Object playerPointsAPI; // PlayerPoints API (反射调用，防止硬依赖)
    private boolean ready = false;
    private String activeType;

    public EconomyManager(XMPayPlugin plugin) {
        this.plugin = plugin;
        setupEconomy();
    }

    public void reload() {
        setupEconomy();
    }

    private void setupEconomy() {
        String primary = plugin.getXMPayConfig().getEconomyPrimary();

        switch (primary.toLowerCase()) {
            case "vault" -> {
                if (setupVault()) {
                    activeType = "vault";
                    ready = true;
                    plugin.getLogger().info("经济系统：已对接 Vault (provider: "
                            + vaultEconomy.getName() + ")");
                } else {
                    plugin.getLogger().warning("Vault 未找到或无经济插件，尝试PlayerPoints...");
                    if (setupPlayerPoints()) {
                        activeType = "playerpoints";
                        ready = true;
                    }
                }
            }
            case "playerpoints" -> {
                if (setupPlayerPoints()) {
                    activeType = "playerpoints";
                    ready = true;
                    plugin.getLogger().info("经济系统：已对接 PlayerPoints");
                } else {
                    plugin.getLogger().warning("PlayerPoints 未找到，尝试Vault...");
                    if (setupVault()) {
                        activeType = "vault";
                        ready = true;
                    }
                }
            }
            default -> {
                plugin.getLogger().info("经济系统：使用自定义模式（仅执行指令）");
                activeType = "custom";
                ready = true;
            }
        }

        if (!ready) {
            plugin.getLogger().warning("未找到可用的经济插件！充值将仅执行自定义指令。");
            activeType = "custom";
            ready = true;
        }
    }

    private boolean setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        try {
            RegisteredServiceProvider<Economy> rsp =
                    plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) return false;
            vaultEconomy = rsp.getProvider();
            return vaultEconomy != null;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Vault初始化失败", e);
            return false;
        }
    }

    private boolean setupPlayerPoints() {
        try {
            Class<?> ppClass = Class.forName("org.black_ixx.playerpoints.PlayerPointsAPI");
            org.bukkit.plugin.Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pp == null) return false;
            playerPointsAPI = pp.getClass().getMethod("getAPI").invoke(pp);
            return playerPointsAPI != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 给玩家发放游戏货币
     */
    public boolean give(UUID playerUUID, String playerName, double amount) {
        if ("vault".equals(activeType) && vaultEconomy != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
            net.milkbowl.vault.economy.EconomyResponse resp =
                    vaultEconomy.depositPlayer(offlinePlayer, amount);
            if (resp.transactionSuccess()) {
                plugin.getLogger().info("Vault发放 " + amount + " 给 " + playerName);
                return true;
            } else {
                plugin.getLogger().warning("Vault发放失败: " + resp.errorMessage);
                return false;
            }
        } else if ("playerpoints".equals(activeType) && playerPointsAPI != null) {
            try {
                int points = (int) (amount * plugin.getXMPayConfig().getPlayerPointsRate() / 100.0);
                playerPointsAPI.getClass()
                        .getMethod("give", UUID.class, int.class)
                        .invoke(playerPointsAPI, playerUUID, points);
                plugin.getLogger().info("PlayerPoints发放 " + points + " 点给 " + playerName);
                return true;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "PlayerPoints发放失败", e);
                return false;
            }
        }
        // custom模式：依赖外部指令处理
        return true;
    }

    /**
     * 扣除玩家货币
     */
    public boolean take(UUID playerUUID, String playerName, double amount) {
        if ("vault".equals(activeType) && vaultEconomy != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
            net.milkbowl.vault.economy.EconomyResponse resp =
                    vaultEconomy.withdrawPlayer(offlinePlayer, amount);
            return resp.transactionSuccess();
        }
        return false;
    }

    /**
     * 查询玩家余额
     */
    public double getBalance(UUID playerUUID) {
        if ("vault".equals(activeType) && vaultEconomy != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
            return vaultEconomy.getBalance(offlinePlayer);
        }
        return 0;
    }

    public boolean isReady() { return ready; }

    public String getActiveType() { return activeType; }

    public String getCurrencyName() {
        if ("vault".equals(activeType) && vaultEconomy != null) {
            return vaultEconomy.currencyNamePlural();
        }
        if ("playerpoints".equals(activeType)) {
            return "点券";
        }
        return "游戏货币";
    }
}
