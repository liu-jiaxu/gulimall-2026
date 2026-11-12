package com.atguigu.gulimall.member;

import com.atguigu.gulimall.api.auth_server.UserRegisterVo;
import com.atguigu.gulimall.member.controller.MemberController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@SpringBootTest
class GulimallMemberApplicationTests {

    @Autowired
    MemberController memberController;

    // 测试注册功能
    @Test
    void register() {

        UserRegisterVo vo = new UserRegisterVo();
        vo.setUserName("testuser");
        vo.setPassword("password123");
        vo.setPhone("13800138000");
        memberController.regist(vo);

    }

    /*
        ========================= 密码存储（慢哈希） =========================
        下面几个都是"不可逆"的，用于存密码。共同点：自带随机盐 + 代价可调。
        校验一律用 matches(raw, encoded)，不要自己拼字符串比较。
     */

    /**
     * 1. BCrypt —— 当前密码存储的主流选择，也是 Spring Security 的默认算法。
     *
     * 注意 BCrypt 不是 MD5，两者毫无关系：
     *   - BCrypt 基于 Blowfish 派生（EksBlowfish），自带随机盐，故意设计得很慢（cost 因子 = 2^cost 轮）
     *   - MD5 是快速摘要，早已被破解，即使自己加盐也不该用于密码
     *
     * 核心特性：同一密码每次 encode() 结果都不同（盐是随机的），所以只能靠 matches() 校验。
     * 存库只需一个 varchar(60) 字段，盐和 cost 都编在哈希串里（格式：$2a$12$...）。
     */
    @Test
    void bcrypt() {
        // cost=12，即 2^12 = 4096 轮；教程常用的默认值是 10。越高越慢越安全，生产建议 10~12
        PasswordEncoder encoder = new BCryptPasswordEncoder(12);
        String rawPassword = "123456";

        String hash1 = encoder.encode(rawPassword);
        String hash2 = encoder.encode(rawPassword);

        System.out.println("hash1              = " + hash1);
        System.out.println("hash2              = " + hash2);
        System.out.println("两次哈希是否相同(应为 false) = " + hash1.equals(hash2));
        System.out.println("哈希长度(应为 60)   = " + hash1.length());
        System.out.println("matches 正确密码    = " + encoder.matches(rawPassword, hash1));
        System.out.println("matches 错误密码    = " + encoder.matches("654321", hash1));
    }

    /**
     * 2. DelegatingPasswordEncoder —— 生产环境更推荐，Spring Security 的官方推荐入口。
     *
     * 存库格式带算法前缀：{bcrypt}$2a$10$xxxx
     * 校验时按前缀自动选算法，带来两个好处：
     *   - 不同算法可以共存：老用户的旧哈希和新用户的哈希放在同一张表里都能校验
     *   - 算法可平滑升级：以后想换 Argon2，只需改 encode 的目标，旧密码仍能登录
     *     （登录成功时发现前缀是旧的就地重算一次并更新，这叫"登录时迁移"）
     */
    @Test
    void delegatingPasswordEncoder() {
        // 默认映射：encode 用 bcrypt，同时支持校验 {bcrypt} / {pbkdf2} / {argon2} 等前缀
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String rawPassword = "123456";

        String hash = encoder.encode(rawPassword);
        System.out.println("hash            = " + hash);   // {bcrypt}$2a$10$...
        System.out.println("matches 自己生成 = " + encoder.matches(rawPassword, hash));

        // 模拟历史数据：带 {bcrypt} 前缀的旧哈希，同一个 encoder 也能校验
        String legacyHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(rawPassword);
        System.out.println("matches 带前缀的旧数据 = " + encoder.matches(rawPassword, legacyHash));
    }

    /**
     * 3. Argon2id —— OWASP 当前推荐的密码哈希首选。
     *
     * 相比 BCrypt 的优势：内存硬（memory-hard），抗 GPU / ASIC 并行爆破更强。
     * 代价：需要 BouncyCastle（本项目 classpath 已有 bcprov-jdk18on 1.84）。
     *
     * 注意 Spring Security 6.5 里无参构造器已废弃，要用 defaultsForSpringSecurity_v5_8()。
     */
    @Test
    void argon2() {
        PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String rawPassword = "123456";

        String hash = encoder.encode(rawPassword);
        System.out.println("hash     = " + hash);
        System.out.println("matches  = " + encoder.matches(rawPassword, hash));
    }

    /**
     * 4. PBKDF2 —— FIPS 合规 / 等保金融场景常用，JDK 原生算法，不依赖第三方库。
     *
     * 安全性弱于 BCrypt 和 Argon2（不抗 GPU，迭代次数可调但内存占用小），
     * 优点是标准、兼容性最好、合规审计最容易过。
     * 同样：无参构造器已废弃，用 defaultsForSpringSecurity_v5_8()。
     */
    @Test
    void pbkdf2() {
        PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String rawPassword = "123456";

        String hash = encoder.encode(rawPassword);
        System.out.println("hash     = " + hash);
        System.out.println("matches  = " + encoder.matches(rawPassword, hash));
    }

    /**
     * 5. 为什么 MD5 / 裸 SHA-256 不能存密码 —— 用耗时对比直观说明。
     *
     * SHA-256 单次只要微秒级，一块 GPU 每秒能试几十亿次，6~8 位密码瞬间爆破；
     * BCrypt 单次故意拖到几十~几百毫秒，把暴力破解成本拉高好几个数量级。
     * 密码存储要的不是"算得快"，恰恰是"算得慢"。
     */
    @Test
    void whyNotMd5OrSha256() throws Exception {
        byte[] raw = "123456".getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        // SHA-256：跑 10 万次，算平均单次耗时
        int shaCount = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < shaCount; i++) {
            digest.digest(raw);   // digest() 会自动 reset，可循环复用
        }
        long shaTotal = System.nanoTime() - start;

        // BCrypt(cost=12)：太慢，跑 5 次估算单次即可
        PasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
        int bcryptCount = 5;
        start = System.nanoTime();
        for (int i = 0; i < bcryptCount; i++) {
            bcrypt.encode("123456");
        }
        long bcryptTotal = System.nanoTime() - start;

        double shaPerOp = (double) shaTotal / shaCount;
        double bcryptPerOp = (double) bcryptTotal / bcryptCount;

        System.out.printf("SHA-256    %,d 次共 %,d ns -> 单次 %.1f ns%n", shaCount, shaTotal, shaPerOp);
        System.out.printf("BCrypt-12  %d 次共 %,d ns -> 单次 %.1f ms%n", bcryptCount, bcryptTotal, bcryptPerOp / 1_000_000);
        System.out.printf("单次耗时差距约 %.0f 倍（破解难度差距同量级）%n", bcryptPerOp / shaPerOp);
    }

    /*
        ========================= 可逆加密 / 签名 =========================
        下面这些不是存密码用的，解决的是另外两类问题。
     */

    /**
     * 6. AES-256-GCM —— 可逆加密，用于必须还原的敏感字段（手机号、身份证、收货地址）。
     *
     * 为什么选 GCM 而不是 CBC：
     *   - GCM 是 AEAD（带认证的加密），机密性 + 完整性一起保证，解密时如果密文被篡改会直接抛异常
     *   - 用 CBC 还得额外挂一个 HMAC 防篡改，容易漏
     * 要点：
     *   - 每次加密都用新的随机 IV（12 字节），IV 不保密但绝不能重复使用
     *   - 存库格式：IV + 密文 拼接后 Base64
     *   - 生产环境密钥必须放 KMS / 配置中心（Nacos 加密配置）并定期轮换，绝不能硬编码在代码里
     */
    @Test
    void aesGcm() throws Exception {
        // 1) 生成 256 位密钥（生产环境应由 KMS 托管，此处仅演示）
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        String plainText = "13800138000";

        // 2) 加密：随机 IV + AES/GCM/NoPadding，认证标签 128 位
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // 3) 落库格式：IV(12B) + 密文(明文长度 + 16B 认证标签)
        byte[] stored = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, stored, 0, iv.length);
        System.arraycopy(cipherText, 0, stored, iv.length, cipherText.length);
        String storedBase64 = Base64.getEncoder().encodeToString(stored);
        System.out.println("密文(Base64) = " + storedBase64);

        // 4) 解密
        byte[] decoded = Base64.getDecoder().decode(storedBase64);
        byte[] ivFromStore = Arrays.copyOfRange(decoded, 0, 12);
        byte[] cipherTextFromStore = Arrays.copyOfRange(decoded, 12, decoded.length);

        Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, ivFromStore));
        String decrypted = new String(decryptCipher.doFinal(cipherTextFromStore), StandardCharsets.UTF_8);

        System.out.println("解密结果     = " + decrypted);
        System.out.println("原样还原     = " + plainText.equals(decrypted));

        // 5) 篡改一个字节再解密，GCM 会因认证失败抛 AEADBadTagException（完整性保护生效）
        byte[] tampered = decoded.clone();
        tampered[tampered.length - 1] ^= 0x01;
        try {
            Cipher t = Cipher.getInstance("AES/GCM/NoPadding");
            t.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(tampered, 0, 12)));
            t.doFinal(Arrays.copyOfRange(tampered, 12, tampered.length));
            System.out.println("篡改检测     = 未检测到（不应该出现）");
        } catch (Exception e) {
            System.out.println("篡改检测     = 已拦截 " + e.getClass().getSimpleName());
        }
    }

    /**
     * 7. HMAC-SHA256 —— 带密钥的摘要，用于签名 / 防篡改（URL 参数签名、回调来源校验、开放接口鉴权）。
     *
     * 与"加密"的区别：不可逆，但验证方必须持有密钥，所以比裸 SHA-256 安全得多
     *   （裸 SHA-256 任何人都能算出结果，等于没有防伪能力）。
     *
     * 你项目里 renren-fast 的 token 用 MD5 做签名（TokenGenerator.java:43），
     * 正确做法就是换成 HMAC-SHA256，或者直接用 JWT（内部也是 HMAC/RSA 签名）。
     *
     * 校验时用 MessageDigest.isEqual 做常量时间比较，避免时序攻击泄漏密钥信息。
     */
    @Test
    void hmacSha256() throws Exception {
        byte[] secret = "gulimall-sign-secret".getBytes(StandardCharsets.UTF_8);
        String payload = "skuId=1&price=199";

        // 服务端/客户端用同一个密钥算签名
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        System.out.println("签名       = " + signature);

        // 验证方重算并比较（常量时间）
        Mac verifyMac = Mac.getInstance("HmacSHA256");
        verifyMac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(verifyMac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        boolean valid = MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
        System.out.println("校验通过   = " + valid);

        // 改一个字节，验签失败
        String tamperedPayload = "skuId=1&price=1";
        Mac tamperMac = Mac.getInstance("HmacSHA256");
        tamperMac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String tamperedSig = Base64.getEncoder().encodeToString(tamperMac.doFinal(tamperedPayload.getBytes(StandardCharsets.UTF_8)));
        System.out.println("篡改后验签 = " + MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                tamperedSig.getBytes(StandardCharsets.UTF_8)));
    }

}
