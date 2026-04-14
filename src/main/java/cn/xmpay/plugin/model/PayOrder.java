package cn.xmpay.plugin.model;

import org.bukkit.inventory.ItemStack;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支付订单数据模型
 */
public class PayOrder {

    public enum Status {
        PENDING,    // 待支付
        PAID,       // 已支付
        CANCELLED,  // 已取消
        TIMEOUT     // 已超时
    }

    public enum PayType {
        ALIPAY("alipay", "支付宝"),
        WXPAY("wxpay", "微信支付"),
        QQPAY("qqpay", "QQ钱包");

        private final String code;
        private final String displayName;

        PayType(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String getCode() { return code; }
        public String getDisplayName() { return displayName; }

        public static PayType fromCode(String code) {
            for (PayType t : values()) {
                if (t.code.equalsIgnoreCase(code)) return t;
            }
            return ALIPAY;
        }
    }

    // 商户订单号（本地生成）
    private final String outTradeNo;
    // 易支付系统订单号
    private String tradeNo;
    // 玩家UUID
    private final UUID playerUUID;
    // 玩家名
    private final String playerName;
    // 商品名称
    private final String productName;
    // 支付金额（元）
    private final double money;
    // 支付方式
    private final PayType payType;
    // 支付跳转URL
    private String payUrl;
    // 二维码URL
    private String qrcodeUrl;
    // 二维码图片URL
    private String qrcodeImg;
    // 订单状态
    private Status status;
    // 创建时间
    private final LocalDateTime createTime;
    // 完成时间
    private LocalDateTime finishTime;
    // 超时时间
    private final LocalDateTime expireTime;
    // 附加参数
    private String param;
    // 地图ID（地图二维码使用）
    private int mapId = -1;
    // 发放地图前主手物品（支付完成后归还）
    private ItemStack originalMainHand;

    public PayOrder(UUID playerUUID, String playerName, String productName,
                    double money, PayType payType, int timeoutMinutes) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.productName = productName;
        this.money = money;
        this.payType = payType;
        this.outTradeNo = generateOrderNo(playerName);
        this.status = Status.PENDING;
        this.createTime = LocalDateTime.now();
        this.expireTime = createTime.plusMinutes(timeoutMinutes);
    }

    /**
     * 生成唯一订单号：时间戳 + 玩家名hash + 随机数
     */
    private static String generateOrderNo(String playerName) {
        long timestamp = System.currentTimeMillis();
        int hash = Math.abs(playerName.hashCode()) % 9999;
        int rand = (int) (Math.random() * 9999);
        return String.format("%d%04d%04d", timestamp, hash, rand);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expireTime);
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isPaid() {
        return status == Status.PAID;
    }

    // ===== Getters & Setters =====

    public String getOutTradeNo() { return outTradeNo; }

    public String getTradeNo() { return tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }

    public UUID getPlayerUUID() { return playerUUID; }

    public String getPlayerName() { return playerName; }

    public String getProductName() { return productName; }

    public double getMoney() { return money; }

    public PayType getPayType() { return payType; }

    public String getPayUrl() { return payUrl; }
    public void setPayUrl(String payUrl) { this.payUrl = payUrl; }

    public String getQrcodeUrl() { return qrcodeUrl; }
    public void setQrcodeUrl(String qrcodeUrl) { this.qrcodeUrl = qrcodeUrl; }

    public String getQrcodeImg() { return qrcodeImg; }
    public void setQrcodeImg(String qrcodeImg) { this.qrcodeImg = qrcodeImg; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }

    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }

    public LocalDateTime getExpireTime() { return expireTime; }

    public String getParam() { return param; }
    public void setParam(String param) { this.param = param; }

    public int getMapId() { return mapId; }
    public void setMapId(int mapId) { this.mapId = mapId; }

    public ItemStack getOriginalMainHand() { return originalMainHand; }
    public void setOriginalMainHand(ItemStack item) { this.originalMainHand = item; }

    @Override
    public String toString() {
        return "PayOrder{outTradeNo='" + outTradeNo + "', player='" + playerName +
                "', money=" + money + ", type=" + payType.getCode() +
                ", status=" + status + "}";
    }
}
