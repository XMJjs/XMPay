package cn.xmpay.plugin;

import cn.xmpay.plugin.api.ZPayAPI;
import cn.xmpay.plugin.command.XMPayAdminCommand;
import cn.xmpay.plugin.command.XMPayCommand;
import cn.xmpay.plugin.config.XMPayConfig;
import cn.xmpay.plugin.economy.EconomyManager;
import cn.xmpay.plugin.listener.PlayerListener;
import cn.xmpay.plugin.map.MapManager;
import cn.xmpay.plugin.model.OrderManager;
import cn.xmpay.plugin.server.CallbackServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * XMPay - 游戏内易支付插件
 * 支持支付宝/微信支付、地图二维码、Vault/点券对接、多身份指令
 *
 * @author XMJ
 * @version 1.0.0
 */
public class XMPayPlugin extends JavaPlugin {

    private static XMPayPlugin instance;

    private XMPayConfig xmPayConfig;
    private ZPayAPI zPayAPI;
    private EconomyManager economyManager;
    private OrderManager orderManager;
    private MapManager mapManager;
    private CallbackServer callbackServer;

    @Override
    public void onEnable() {
        instance = this;

        // 打印启动横幅
        printBanner();

        // 保存默认配置
        saveDefaultConfig();

        // 初始化配置
        xmPayConfig = new XMPayConfig(this);

        // 初始化订单管理器
        orderManager = new OrderManager(this);

        // 初始化经济系统（需等待插件加载完成）
        getServer().getScheduler().runTask(this, () -> {
            economyManager = new EconomyManager(this);
            if (!economyManager.isReady()) {
                getLogger().warning("经济插件未找到！部分功能可能不可用。");
            }
        });

        // 初始化支付API
        zPayAPI = new ZPayAPI(this);

        // 初始化地图管理器
        mapManager = new MapManager(this);

        // 启动内置HTTP回调服务器
        if (xmPayConfig.isHttpServerEnabled()) {
            callbackServer = new CallbackServer(this);
            callbackServer.start();
        }

        // 注册指令
        registerCommands();

        // 注册事件监听器
        registerListeners();

        // 启动订单超时检查任务
        startOrderTimeoutTask();

        getLogger().info("XMPay 已成功启动！版本: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        // 停止HTTP服务器
        if (callbackServer != null) {
            callbackServer.stop();
        }

        // 保存所有待处理订单
        if (orderManager != null) {
            orderManager.saveAll();
        }

        getLogger().info("XMPay 已关闭。");
    }

    /**
     * 重载插件配置
     */
    public void reload() {
        reloadConfig();
        xmPayConfig.reload();
        zPayAPI.reload();
        economyManager.reload();

        // 重启HTTP服务器
        if (callbackServer != null) {
            callbackServer.stop();
        }
        if (xmPayConfig.isHttpServerEnabled()) {
            callbackServer = new CallbackServer(this);
            callbackServer.start();
        }

        getLogger().info("配置已重载！");
    }

    private void registerCommands() {
        XMPayCommand mainCmd = new XMPayCommand(this);
        getCommand("xmpay").setExecutor(mainCmd);
        getCommand("xmpay").setTabCompleter(mainCmd);

        XMPayAdminCommand adminCmd = new XMPayAdminCommand(this);
        getCommand("xmpayadmin").setExecutor(adminCmd);
        getCommand("xmpayadmin").setTabCompleter(adminCmd);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    private void startOrderTimeoutTask() {
        long intervalTicks = 20L * 60; // 每分钟检查一次
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                orderManager.checkTimeouts();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "订单超时检查时发生错误", e);
            }
        }, 20L * 60, intervalTicks);
    }

    private void printBanner() {
        getLogger().info("  __  ____  __ ____  _ ");
        getLogger().info(" \\ \\/ /  \\/  |  _ \\/ \\| |/ /");
        getLogger().info("  \\  /| |\\/| | |_) |  _  / / ");
        getLogger().info("  /  \\| |  | |  __/| | | \\ \\  ");
        getLogger().info(" /_/\\_\\_|  |_|_|   |_| |_|\\_\\");
        getLogger().info(" 游戏内易支付插件 v" + getDescription().getVersion());
        getLogger().info(" 作者: XMJ | Paper 1.20+");
    }

    // ========== Getters ==========

    public static XMPayPlugin getInstance() {
        return instance;
    }

    public XMPayConfig getXMPayConfig() {
        return xmPayConfig;
    }

    public ZPayAPI getZPayAPI() {
        return zPayAPI;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public OrderManager getOrderManager() {
        return orderManager;
    }

    public MapManager getMapManager() {
        return mapManager;
    }

    public CallbackServer getCallbackServer() {
        return callbackServer;
    }
}
