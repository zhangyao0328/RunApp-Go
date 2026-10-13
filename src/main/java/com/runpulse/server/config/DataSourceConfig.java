package com.runpulse.server.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(RunpulseProperties props) {
        // 与 Go DSN `user:pass@tcp(host:port)/db?charset=utf8mb4&parseTime=True&loc=Local` 对应；
        // Java 侧编码固定 UTF-8（覆盖 utf8mb4 字符集），时区用 connectionTimeZone=LOCAL 对应 loc=Local
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=LOCAL",
                props.getDbHost(), props.getDbPort(), props.getDbName()));
        ds.setUsername(props.getDbUser());
        ds.setPassword(props.getDbPassword());
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        // 启动即测试连接，行为与 Go 的 db.Ping() 一致：连不上直接启动失败
        try (Connection ignored = ds.getConnection()) {
            log.info("数据库连接成功 {}:{}", props.getDbHost(), props.getDbPort());
        } catch (Exception e) {
            throw new IllegalStateException("打开数据库失败: " + e.getMessage(), e);
        }
        return ds;
    }
}
