# SelfMusic COS 签名服务（Legacy）

> ⚠️ 已弃用：Android 客户端已改为自建服务器模式，不再调用本服务。新后端在 [SelfMusicServer](../../README.md)；本目录仅作历史参考。

这是面向单用户首版的腾讯云 SCF Node.js 函数。它将歌曲清单保存在 COS 的
`users/{userId}/_index/songs.json`，向 Android 返回短期 PUT/GET 签名 URL。

## 部署

1. 在本目录运行 `npm install --omit=dev`，将整个目录压缩为 ZIP。
2. 创建 Node.js 18 或更高版本的 Web 函数，入口填 `index.main_handler`。
3. 配置环境变量，参照 `.env.example`；真实密钥只填在 SCF 控制台。
4. 给函数角色或专用 CAM 身份授予清单文档中的最小 COS 权限。
5. 创建 API 网关触发器，路径前缀使用 `/v1`，仅启用 HTTPS。
6. 使用 API 网关的实际基础地址配置 Android 的 `CLOUD_API_BASE_URL`。

所有请求必须携带 `Authorization: Bearer <API_TOKEN>`。首版的 API Token 是单用户认证，正式多用户版本应替换为登录 JWT，并将清单从 JSON 升级为数据库。
