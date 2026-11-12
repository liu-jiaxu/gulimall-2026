package com.atguigu.gulimall.k8s.user;

import com.atguigu.gulimall.k8s.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * ai_user 表的读写。
 *
 * <p>只需要用户、密码哈希、展示名三个业务字段，用 JdbcTemplate 足够。
 */
@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 按用户名查询用户。
     *
     * @param username 登录名
     * @return 用户记录，不存在时为空
     */
    public Optional<UserRow> findByUsername(String username) {
        // 1. 用户名有唯一索引，最多一条
        String sql = "SELECT id, username, password_hash, display_name, enabled FROM ai_user WHERE username = ?";
        List<UserRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new UserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getBoolean("enabled")
        ), username);
        return rows.stream().findFirst();
    }

    /**
     * 判断用户名是否已存在。
     *
     * @param username 登录名
     * @return 存在时为 true
     */
    public boolean existsByUsername(String username) {
        // 1. 只查数量，避免把整行数据读出来
        String sql = "SELECT COUNT(*) FROM ai_user WHERE username = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, username);
        return count != null && count > 0;
    }

    /**
     * 新增用户并返回数据库生成的主键。
     *
     * @param username     登录名
     * @param passwordHash BCrypt 密码哈希
     * @param displayName  页面展示名
     * @return 新用户主键
     */
    public long insert(String username, String passwordHash, String displayName) {
        // 1. 用 RETURN_GENERATED_KEYS 拿自增主键，后面写 Session 和 Redis 索引都要用
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ai_user (username, password_hash, display_name) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, displayName);
            return ps;
        }, keyHolder);

        // 2. 主键拿不到说明插入异常，直接报错而不是返回 0
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(500, "创建用户失败：没有拿到主键");
        }
        return key.longValue();
    }

    /**
     * 按用户名更新密码哈希。
     *
     * @param username     登录名
     * @param passwordHash 新的 BCrypt 密码哈希
     */
    public void updatePassword(String username, String passwordHash) {
        // 1. 只更新密码字段，其它信息保持不变
        jdbcTemplate.update("UPDATE ai_user SET password_hash = ? WHERE username = ?", passwordHash, username);
    }
}
