package com.atguigu.gulimall.k8s.config;

import com.atguigu.gulimall.k8s.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时准备默认数据。
 *
 * <p>1. 没有默认管理员时创建一个；
 * 2. 没有 AI 默认配置时插入一行；
 * 3. 如果表还没建，打印提示但不阻止服务启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AiConfigRepository aiConfigRepository;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;

    /**
     * 应用启动后执行一次初始化。
     *
     * @param args 启动参数
     */
    @Override
    public void run(String... args) {
        try {
            // 1. 没有默认用户就创建
            String username = authProperties.getSeedUsername();
            if (!userRepository.existsByUsername(username)) {
                userRepository.insert(
                        username,
                        passwordEncoder.encode(authProperties.getSeedPassword()),
                        "管理员"
                );
                log.info("已创建默认用户 {}，密码见 application.yaml 的 gulimall.k8s.auth.seed-password", username);
            }

            // 2. 没有 AI 默认配置就插入默认模型和系统提示词
            AiConfig config = aiConfigRepository.load();
            log.info("AI 默认模型：{}", config.defaultModel());
        } catch (DataAccessException ex) {
            // 3. 表还没建时不阻止启动，只提示执行 SQL
            String reason = ex.getMostSpecificCause().getMessage();
            log.warn("初始化跳过：请先执行 gulimall-k8s/sql/init.sql。原因：{}", reason);
        }
    }
}
