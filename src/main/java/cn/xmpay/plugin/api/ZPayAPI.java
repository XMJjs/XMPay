package cn.xmpay.plugin.api;

import cn.xmpay.plugin.XMPayPlugin;
import cn.xmpay.plugin.config.XMPayConfig;
import cn.xmpay.plugin.model.PayOrder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 易支付(ZPay) API 对接核心
 * 支持：页面跳转支付、API接口支付（获取二维码）、订单查询、退款
 *
 * 签名算法：
 * 1. 参数按ASCII码升序排序
 * 2. 拼接成 key=value&key2=value2
 * 3. 末尾拼接商户KEY
 * 4. MD5(小写)
 */
public class ZPayAPI {

    private final XMPayPlugin plugin;
    private final Gson gson = new Gson();
    private OkHttpClient httpClient;

    public ZPayAPI(XMPayPlugin plugin) {
        this.plugin = plugin;
        buildHttpClient();
    }

    /**
     * 检查支付服务是否可用（配置完整性检查）
     * @return 检查结果，包含失败原因
     */
    public ServiceCheckResult checkServiceAvailability() {
        XMPayConfig cfg = plugin.getXMPayConfig();

        if (cfg.getPid() == null || cfg.getPid().trim().isEmpty()) {
            return new ServiceCheckResult(false, "商户ID未配置 (payment.pid)");
        }
        if (cfg.getKey() == null || cfg.getKey().trim().isEmpty()) {
            return new ServiceCheckResult(false, "商户密钥未配置 (payment.key)");
        }
        if (cfg.getApiUrl() == null || cfg.getApiUrl().trim().isEmpty()) {
            return new ServiceCheckResult(false, "API地址未配置 (payment.api-url)");
        }
        String host = cfg.getPublicHost();
        if (host == null || host.trim().isEmpty()) {
            return new ServiceCheckResult(false,
                    "公网地址未配置 (http-server.public-host)，回调通知将无法送达");
        }
        return new ServiceCheckResult(true, "配置正常");
    }

    /**
     * 测试支付API连通性（实际请求一次下单接口，金额设为0.01测试）
     * @return 测试结果
     */
    public ApiResult testConnection() {
        XMPayConfig cfg = plugin.getXMPayConfig();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", cfg.getPid());
        params.put("type", "alipay");
        params.put("out_trade_no", "test_" + System.currentTimeMillis());
        params.put("notify_url", cfg.buildNotifyUrl());
        params.put("name", "[测试] XMPay连通性检测");
        params.put("money", "0.01");
        params.put("clientip", "127.0.0.1");
        params.put("device", "pc");

        String sign = generateSign(params, cfg.getKey());
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            formBuilder.add(entry.getKey(), entry.getValue());
        }

        Request request = new Request.Builder()
                .url(cfg.getApiUrl() + "/mapi.php")
                .post(formBuilder.build())
                .addHeader("User-Agent", "XMPay/1.0 Minecraft-Plugin (Test)")
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            String body = response.body() != null ? response.body().string() : "{}";
            plugin.getLogger().info("[ZPayAPI] 测试响应: " + body);

            JsonObject json = gson.fromJson(body, JsonObject.class);
            int code = json.has("code") ? json.get("code").getAsInt() : -1;
            String msg = json.has("msg") ? json.get("msg").getAsString() : "未知错误";
            return new ApiResult(code == 1, msg);
        } catch (IOException e) {
            return new ApiResult(false, "网络连接失败: " + e.getMessage());
        }
    }

    /**
     * 服务可用性检查结果
     */
    public static class ServiceCheckResult {
        public final boolean available;
        public final String message;
        public ServiceCheckResult(boolean available, String message) {
            this.available = available;
            this.message = message;
        }
    }

    public void reload() {
        buildHttpClient();
    }

    private void buildHttpClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    // ========================================
    // 核心接口：API接口支付（获取二维码/跳转URL）
    // ========================================

    /**
     * 通过 mapi.php 接口发起支付，获取二维码或跳转URL
     *
     * @param order 订单对象
     * @param clientIp 玩家IP（可传"127.0.0.1"）
     * @return API响应结果
     */
    public ApiResult createPayment(PayOrder order, String clientIp) {
        XMPayConfig cfg = plugin.getXMPayConfig();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", cfg.getPid());
        params.put("type", order.getPayType().getCode());
        params.put("out_trade_no", order.getOutTradeNo());
        params.put("notify_url", cfg.buildNotifyUrl());
        params.put("name", order.getProductName());
        params.put("money", String.format("%.2f", order.getMoney()));
        params.put("clientip", clientIp != null ? clientIp : "127.0.0.1");
        params.put("device", "pc");

        if (cfg.getCid() != null && !cfg.getCid().isEmpty()) {
            params.put("cid", cfg.getCid());
        }
        if (order.getParam() != null && !order.getParam().isEmpty()) {
            params.put("param", order.getParam());
        }

        // 生成签名
        String sign = generateSign(params, cfg.getKey());
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        // 构建表单请求
        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            formBuilder.add(entry.getKey(), entry.getValue());
        }

        Request request = new Request.Builder()
                .url(cfg.getApiUrl() + "/mapi.php")
                .post(formBuilder.build())
                .addHeader("User-Agent", "XMPay/1.0 Minecraft-Plugin")
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            String body = response.body() != null ? response.body().string() : "{}";

            if (cfg.isDebug()) {
                plugin.getLogger().info("[ZPayAPI] mapi响应: " + body);
            }

            JsonObject json = gson.fromJson(body, JsonObject.class);
            int code = json.has("code") ? json.get("code").getAsInt() : -1;

            if (code == 1) {
                ApiResult result = new ApiResult(true, "success");
                if (json.has("payurl")) result.payUrl = json.get("payurl").getAsString();
                if (json.has("qrcode")) result.qrcodeUrl = json.get("qrcode").getAsString();
                if (json.has("img")) result.qrcodeImg = json.get("img").getAsString();
                if (json.has("O_id")) result.oid = json.get("O_id").getAsString();
                if (json.has("trade_no")) result.tradeNo = json.get("trade_no").getAsString();
                return result;
            } else {
                String msg = json.has("msg") ? json.get("msg").getAsString() : "未知错误";
                return new ApiResult(false, msg);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "支付API请求失败", e);
            return new ApiResult(false, "网络连接失败: " + e.getMessage());
        }
    }

    // ========================================
    // 生成页面跳转支付URL（GET方式）
    // ========================================

    /**
     * 生成页面跳转支付URL（用于显示给玩家直接访问）
     */
    public String buildSubmitUrl(PayOrder order) {
        XMPayConfig cfg = plugin.getXMPayConfig();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("money", String.format("%.2f", order.getMoney()));
        params.put("name", order.getProductName());
        params.put("notify_url", cfg.buildNotifyUrl());
        params.put("out_trade_no", order.getOutTradeNo());
        params.put("pid", cfg.getPid());
        params.put("return_url", cfg.getReturnUrl().isEmpty()
                ? cfg.buildNotifyUrl() : cfg.getReturnUrl());
        params.put("type", order.getPayType().getCode());

        String sign = generateSign(params, cfg.getKey());

        StringBuilder sb = new StringBuilder(cfg.getApiUrl() + "/submit.php?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sb.append(entry.getKey()).append("=")
              .append(urlEncode(entry.getValue())).append("&");
        }
        sb.append("sign=").append(sign).append("&sign_type=MD5");

        return sb.toString();
    }

    // ========================================
    // 查询订单
    // ========================================

    /**
     * 查询订单状态
     */
    public QueryResult queryOrder(String outTradeNo) {
        XMPayConfig cfg = plugin.getXMPayConfig();

        String url = cfg.getApiUrl() + "/api.php?act=order"
                + "&pid=" + cfg.getPid()
                + "&key=" + cfg.getKey()
                + "&out_trade_no=" + outTradeNo;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "XMPay/1.0 Minecraft-Plugin")
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            String body = response.body() != null ? response.body().string() : "{}";

            if (cfg.isDebug()) {
                plugin.getLogger().info("[ZPayAPI] 查询响应: " + body);
            }

            JsonObject json = gson.fromJson(body, JsonObject.class);
            int code = json.has("code") ? json.get("code").getAsInt() : -1;

            if (code == 1) {
                QueryResult result = new QueryResult(true);
                result.outTradeNo = json.has("out_trade_no") ? json.get("out_trade_no").getAsString() : "";
                result.tradeNo = json.has("trade_no") ? json.get("trade_no").getAsString() : "";
                result.status = json.has("status") ? json.get("status").getAsInt() : 0;
                result.money = json.has("money") ? json.get("money").getAsString() : "0";
                result.type = json.has("type") ? json.get("type").getAsString() : "";
                result.name = json.has("name") ? json.get("name").getAsString() : "";
                return result;
            } else {
                return new QueryResult(false);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "查询订单失败", e);
            return new QueryResult(false);
        }
    }

    // ========================================
    // 退款
    // ========================================

    /**
     * 提交退款申请
     */
    public ApiResult refundOrder(String outTradeNo, String money) {
        XMPayConfig cfg = plugin.getXMPayConfig();

        RequestBody body = new FormBody.Builder()
                .add("pid", cfg.getPid())
                .add("key", cfg.getKey())
                .add("out_trade_no", outTradeNo)
                .add("money", money)
                .build();

        Request request = new Request.Builder()
                .url(cfg.getApiUrl() + "/api.php?act=refund")
                .post(body)
                .addHeader("User-Agent", "XMPay/1.0 Minecraft-Plugin")
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body() != null ? response.body().string() : "{}";
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            int code = json.has("code") ? json.get("code").getAsInt() : -1;
            String msg = json.has("msg") ? json.get("msg").getAsString() : "";
            return new ApiResult(code == 1, msg);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "退款请求失败", e);
            return new ApiResult(false, "网络错误: " + e.getMessage());
        }
    }

    // ========================================
    // 签名验证（用于回调通知）
    // ========================================

    /**
     * 验证回调通知签名
     *
     * @param params 回调参数（不含sign、sign_type）
     * @param receivedSign 接收到的签名
     * @return 是否合法
     */
    public boolean verifyNotifySign(Map<String, String> params, String receivedSign) {
        String mySign = generateSign(params, plugin.getXMPayConfig().getKey());
        return mySign.equalsIgnoreCase(receivedSign);
    }

    // ========================================
    // 签名算法核心
    // ========================================

    /**
     * 生成MD5签名
     * 规则：参数按key ASCII升序排序 → 拼接 key=value&... → 末尾加KEY → MD5小写
     */
    public static String generateSign(Map<String, String> params, String key) {
        // 过滤空值、sign、sign_type
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (k.equals("sign") || k.equals("sign_type")) continue;
            if (v == null || v.isEmpty()) continue;
            sorted.put(k, v);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        sb.append(key);

        return md5(sb.toString());
    }

    public static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5加密失败", e);
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // ========================================
    // 结果数据类
    // ========================================

    public static class ApiResult {
        public final boolean success;
        public final String message;
        public String payUrl;
        public String qrcodeUrl;
        public String qrcodeImg;
        public String oid;
        public String tradeNo;

        public ApiResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class QueryResult {
        public final boolean success;
        public String outTradeNo;
        public String tradeNo;
        public int status; // 1=已支付，0=未支付
        public String money;
        public String type;
        public String name;

        public QueryResult(boolean success) {
            this.success = success;
        }

        public boolean isPaid() {
            return success && status == 1;
        }
    }
}
