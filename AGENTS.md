# runpulse-server —— 后端约束

## 技术栈

**Kotlin** + Spring Boot 3.3 + Spring MVC + **JdbcTemplate**（非 JPA/MyBatis）+ Maven + MySQL 8。

> 原 Java 代码逐步迁移中：新代码一律用 Kotlin，与存量 Java 代码共存于同一工程（同一 `src/main/java/com/runpulse/` 包路径下混编），不要大规模重写未涉及的 Java 文件。

## Kotlin 编码规则

1. **新文件一律 `.kt`**，遵循 Kotlin 惯用写法：不可变优先（`val`/`data class`）、空安全（避免 `!!`，可空类型显式标注）、扩展函数与顶层函数替代 Java 式静态工具类。
2. 与 Java 互操作：对存量 Java 类从 Kotlin 调用时，注意可空性与 Java 原始类型映射；新增公共接口优先用 Kotlin 类型，确需兼容 Java 调用方再加 `@JvmStatic`/`@JvmField`。
3. Spring 注入用构造器注入（Kotlin 单构造器可省略 `@Autowired`），Bean 定义与配置类可用 `@ConfigurationProperties` + `data class`。
4. 数据表前缀：业务表 `rp_*`、用户角色表 `sys_*`，新表必须遵守；SQL 一律走 JdbcTemplate 参数绑定，禁止字符串拼接 SQL。
5. 双端口设计：App API `:18080`（`/api/v1/*`，X-API-Key 鉴权）与管理后台 `:18081`（`/admin/api/*`，JWT）相互独立，新增接口必须明确归属哪一侧，不要混用鉴权方式。

## 目录约定

- 源码包：`com.runpulse` 包路径不变：Java 在 `src/main/java/`，**Kotlin 放 `src/main/kotlin/`**。
- 管理后台前端产物：`src/main/resources/static/admin/`（由 `frontend/admin-web` 构建后放入，**不要手工改这里的产物**）。

## 常用命令

```bash
# 本地开发（JDK 17，配置在 .env.local）
./scripts/start-local-dev.sh runpulse

# 构建 / 测试
mvn clean package    # Kotlin 与 Java 混编由 kotlin-maven-plugin 处理
mvn test
```

## 注意事项

- 本机默认 java 可能是 Java 8，构建前先 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- `deploy.sh` 在 MySQL `gva` 不可用时自动回滚，不要绕过它直接部署。
- Kotlin 混编已在 `pom.xml` 配好：`kotlin-maven-plugin`（jvmTarget 17、spring all-open、`-Xjsr305=strict`）+ `kotlin-stdlib`/`kotlin-reflect`/`jackson-module-kotlin`。**新 Kotlin 文件放 `src/main/kotlin/`**（已配置同时扫描，与 `src/main/java` 混编互调）。
- 详见根目录 `docs/ARCHITECTURE.md` 与 `README.md`。
