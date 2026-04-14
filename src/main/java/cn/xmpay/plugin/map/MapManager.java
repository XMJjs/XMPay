package cn.xmpay.plugin.map;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.model.PayOrder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.logging.Level;

/**
 * 地图二维码管理器
 * 在 Minecraft 地图上渲染支付二维码
 * 支持显示支付金额、支付方式标识
 */
public class MapManager {

    private final XMPayPlugin plugin;
    // 活跃的地图渲染器：mapId -> renderer
    private final Map<Integer, QRCodeMapRenderer> activeRenderers = new HashMap<>();

    public MapManager(XMPayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 给玩家发放包含支付二维码的地图物品
     *
     * @param player 目标玩家
     * @param order 支付订单（需要已有qrcodeUrl或payUrl）
     * @return 是否成功创建地图
     */
    public boolean giveQRCodeMap(Player player, PayOrder order) {
        if (!plugin.getXMPayConfig().isMapEnabled()) return false;

        // 确定二维码内容（优先用qrcodeUrl）
        String qrContent = order.getQrcodeUrl();
        if (qrContent == null || qrContent.isEmpty()) {
            qrContent = order.getPayUrl();
        }
        if (qrContent == null || qrContent.isEmpty()) {
            plugin.getLogger().warning("订单无二维码URL: " + order.getOutTradeNo());
            return false;
        }

        try {
            // 生成二维码图像（128x128像素）
            BufferedImage qrImage = generateQRCode(qrContent, 128, 128);

            // 创建地图
            org.bukkit.map.MapView mapView = Bukkit.createMap(player.getWorld());
            mapView.setScale(MapView.Scale.CLOSEST);
            mapView.setTrackingPosition(false);
            mapView.setUnlimitedTracking(false);

            // 清除默认渲染器
            for (MapRenderer r : mapView.getRenderers()) {
                mapView.removeRenderer(r);
            }

            // 添加自定义渲染器
            String payTypeName = order.getPayType().getDisplayName();
            double money = order.getMoney();
            QRCodeMapRenderer renderer = new QRCodeMapRenderer(qrImage, payTypeName,
                    money, plugin.getXMPayConfig().isMapShowAmount(),
                    plugin.getXMPayConfig().isMapShowTypeIcon());
            mapView.addRenderer(renderer);
            activeRenderers.put(mapView.getId(), renderer);

            // 保存地图ID到订单
            order.setMapId(mapView.getId());

            // 创建地图物品
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            meta.setMapView(mapView);
            meta.setDisplayName(XMPayPlugin.getInstance().getXMPayConfig()
                    .colorize("&6[" + payTypeName + " 支付码] &e¥" + String.format("%.2f", money)));
            List<String> lore = new ArrayList<>();
            lore.add(XMPayPlugin.getInstance().getXMPayConfig().colorize("&7手持此地图扫码支付"));
            lore.add(XMPayPlugin.getInstance().getXMPayConfig().colorize("&7订单号: &f" + order.getOutTradeNo()));
            lore.add(XMPayPlugin.getInstance().getXMPayConfig().colorize("&c支付完成后地图自动失效"));
            meta.setLore(lore);
            mapItem.setItemMeta(meta);

            // 给予玩家
            player.getInventory().addItem(mapItem);

            // 设置自动过期（若配置了显示时长）
            int duration = plugin.getXMPayConfig().getMapDisplayDuration();
            if (duration > 0) {
                scheduleMapExpiry(player, order, duration);
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "创建支付地图失败", e);
            return false;
        }
    }

    /**
     * 使用 ZXing 生成二维码图像
     */
    public static BufferedImage generateQRCode(String content, int width, int height)
            throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.createGraphics();

        Graphics2D g = (Graphics2D) image.getGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (bitMatrix.get(x, y)) {
                    g.fillRect(x, y, 1, 1);
                }
            }
        }
        g.dispose();
        return image;
    }

    private void scheduleMapExpiry(Player player, PayOrder order, int durationSeconds) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            // 如果订单还在等待状态，通知玩家地图已过期
            if (order.isPending()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getXMPayConfig().getMessage("payment-timeout"));
                    }
                });
            }
            // 移除渲染器
            activeRenderers.remove(order.getMapId());
        }, 20L * durationSeconds);
    }

    /**
     * 地图二维码渲染器
     */
    public static class QRCodeMapRenderer extends MapRenderer {

        private final BufferedImage qrImage;
        private final String payTypeName;
        private final double money;
        private final boolean showAmount;
        private final boolean showTypeIcon;
        private boolean rendered = false;

        public QRCodeMapRenderer(BufferedImage qrImage, String payTypeName,
                                 double money, boolean showAmount, boolean showTypeIcon) {
            super(false);
            this.qrImage = qrImage;
            this.payTypeName = payTypeName;
            this.money = money;
            this.showAmount = showAmount;
            this.showTypeIcon = showTypeIcon;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (rendered) return;
            rendered = true;

            // 地图画布为128x128像素

            // 绘制白色背景
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    canvas.setPixel(x, y, MapPalette.WHITE);
                }
            }

            // 计算二维码绘制区域（顶部留出标题区域）
            int topOffset = showAmount || showTypeIcon ? 16 : 4;
            int qrSize = 128 - topOffset - 4;
            int qrStartX = (128 - qrSize) / 2;

            // 将二维码像素映射到地图画布
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    int imgX = (int) ((double) x / qrSize * qrImage.getWidth());
                    int imgY = (int) ((double) y / qrSize * qrImage.getHeight());
                    imgX = Math.min(imgX, qrImage.getWidth() - 1);
                    imgY = Math.min(imgY, qrImage.getHeight() - 1);

                    int rgb = qrImage.getRGB(imgX, imgY);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    // 简单判断黑白
                    byte mapColor = (r + g + b < 384) ? MapPalette.BLACK : MapPalette.WHITE;
                    canvas.setPixel(qrStartX + x, topOffset + y, mapColor);
                }
            }

            // 绘制标题文字
            if (showTypeIcon || showAmount) {
                String title = (showTypeIcon ? payTypeName + " " : "") +
                               (showAmount ? "¥" + String.format("%.2f", money) : "");
                // 在顶部区域绘制文字（Map Canvas的drawText）
                canvas.drawText(2, 2, MinecraftFont.Font, title);
            }

            // 底部绘制提示
            canvas.drawText(2, 120, MinecraftFont.Font, "Scan to Pay");
        }
    }
}
