# XMPay - 游戏内易支付插件

> 🤖 由 AI 编写 | 欢迎 Fork 并提出建议！

![Build Status](https://github.com/XMJjs/XMPay/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Paper](https://img.shields.io/badge/Paper-1.20%2B-green.svg)
![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)

> 🎮 基于易支付平台的 Minecraft Paper 服务端充值插件
> 支持支付宝、微信支付 | 地图二维码 | Vault/点券对接 | 多身份权限指令

---

## ✨ 功能特性

| 功能 | 描述 |
|------|------|
| 💳 多支付方式 | 支持支付宝、微信支付（可扩展） |
| 🗺️ 地图二维码 | 在游戏地图上渲染支付二维码，手持即扫 |
| 💰 经济对接 | 支持 Vault（EssX等）、PlayerPoints 点券 |
| 🔐 多身份指令 | 根据玩家权限（默认/VIP/管理员）执行不同指令 |
| 🛡️ 安全验证 | MD5签名验证、IP白名单、操作冷却、X-Forwarded-For 代理识别 |
| ⚙️ 高度可配 | 汇率、手息费、超时时间、商户信息均可自定义 |
| 🔒 物品保护 | 地图强制主手持有，掉落自动取消订单，背包满时拒绝发地图 |

---

## 📋 环境要求

- **服务端**: Paper 1.20.1+ (推荐最新版)
- **Java**: Java 17+
- **经济插件** (可选): Vault + 任意经济插件（EssentialsX等）或 PlayerPoints

---

## 🚀 快速开始

### 1. 安装插件

将 `XMPay-x.x.x.jar` 放入服务器 `plugins/` 目录，启动服务器。

### 2. 配置商户信息

编辑 `plugins/XMPay/config.yml`：

```yaml
payment:
  api-url: "https://zpayz.cn"   # 易支付平台地址
  pid: "你的商户ID"              # 在易支付后台查看
  key: "你的商户密钥"            # 在易支付后台查看
  default-type: "alipay"        # 默认支付方式
```

### 3. 配置回调地址

**方式A（推荐）：填写公网地址**
```yaml
http-server:
  enabled: true
  port: 25566
  public-host: "你的服务器公网IP"   # 例如: 1.2.3.4
```
> 回调URL将自动生成为: `http://1.2.3.4:25566/xmpay/notify`

**方式B：手动指定**
```yaml
payment:
  notify-url: "http://你的域名/xmpay/notify"
```

### 4. 防火墙

确保回调端口（默认 25566）对外开放。

---

## ⚙️ 配置详解

### 经济系统对接

```yaml
economy:
  primary: "vault"      # vault | playerpoints | custom
  rate: 100.0           # 1元人民币 = 100 游戏货币
  fee-enabled: false    # 是否收手续费
  fee-rate: 0.0         # 手续费率（百分比）
  min-amount: 0.01      # 最低充值金额（元）
  max-amount: 9999.00   # 最高充值金额（元）
```

### 身份权限指令

支持根据玩家身份执行不同指令：

```yaml
roles:
  default:                             # 普通玩家
    on-payment-success:
      - "eco give {player} {amount}"   # 支付成功后执行
    on-payment-start: []               # 支付前执行

  vip:                                 # 拥有 xmpay.vip 权限
    on-payment-success:
      - "eco give {player} {amount}"
      - "lp user {player} permission set some.perm true"

  admin:                               # 拥有 xmpay.admin 权限
    on-payment-success:
      - "eco give {player} {amount}"
```

**指令占位符：**
| 占位符 | 说明 |
|--------|------|
| `{player}` | 玩家名 |
| `{amount}` | 发放的游戏货币数量 |
| `{money}` | 支付的人民币金额 |
| `{order_no}` | 订单号 |
| `{type}` | 支付方式代码（alipay/wxpay） |

---

## 📌 指令列表

### 玩家指令

| 指令 | 说明 | 权限 |
|------|------|------|
| `/xmpay` | 打开帮助菜单 | `xmpay.use` |
| `/xmpay pay <金额> [方式]` | 自定义金额支付 | `xmpay.pay` |
| `/xmpay order` | 查看当前订单 | `xmpay.order.query` |
| `/xmpay order cancel` | 取消当前订单 | `xmpay.order.query` |
| `/xmpay order query <单号>` | 查询订单状态 | `xmpay.order.query` |
| `/xmpay types` | 查看支持的支付方式 | `xmpay.use` |
| `/xmpay help` | 显示帮助 | `xmpay.use` |

### 管理员指令

| 指令 | 说明 | 权限 |
|------|------|------|
| `/xmpayadmin reload` | 重载配置 | `xmpay.admin` |
| `/xmpayadmin info` | 查看插件状态 | `xmpay.admin` |
| `/xmpayadmin orders` | 查看所有活跃订单 | `xmpay.admin` |
| `/xmpayadmin order <单号>` | 查看订单详情 | `xmpay.admin` |
| `/xmpayadmin cancel <单号>` | 取消指定订单 | `xmpay.admin` |
| `/xmpayadmin refund <单号>` | 申请退款 | `xmpay.order.refund` |
| `/xmpayadmin rate <汇率>` | 临时修改汇率 | `xmpay.admin` |
| `/xmpayadmin pay <玩家> <金额> [方式]` | 为玩家发起支付 | `xmpay.admin` |

---

## 🔑 权限节点

| 权限 | 说明 | 默认 |
|------|------|------|
| `xmpay.use` | 使用基本功能 | 所有人 |
| `xmpay.pay` | 发起支付 | 所有人 |
| `xmpay.order.query` | 查询订单 | 所有人 |
| `xmpay.order.refund` | 申请退款 | OP |
| `xmpay.admin` | 管理员功能 | OP |
| `xmpay.vip` | VIP身份（绑定VIP指令） | 否 |
| `xmpay.bypass` | 跳过手续费 | OP |

---

## 🛠️ 自行编译

```bash
git clone https://github.com/XMJjs/XMPay.git
cd XMPay
mvn clean package
# 输出文件: target/XMPay-x.x.x.jar
```

---

## 📞 支持

- Issues: [GitHub Issues](https://github.com/XMJjs/XMPay/issues)
- 易支付文档: [CY易支付](https://mzf.lovexmj.top/index.php/doc)

---

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE)
