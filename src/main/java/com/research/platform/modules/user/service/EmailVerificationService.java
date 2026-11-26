package com.research.platform.modules.user.service;

import com.research.platform.common.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class EmailVerificationService {
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redis;
    private final String fromAddress;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(
            JavaMailSender mailSender,
            StringRedisTemplate redis,
            @Value("${spring.mail.username}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.redis = redis;
        this.fromAddress = fromAddress;
    }

    public void sendCode(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        String suffix = keySuffix(normalizedEmail);
        String throttleKey = "auth:email-code:throttle:" + suffix;
        Boolean acquired = redis.opsForValue().setIfAbsent(throttleKey, "1", SEND_INTERVAL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(normalizedEmail);
            message.setSubject("ScholarAI 注册邮箱验证码");
            message.setText("你的邮箱验证码是：" + code + "，10分钟内有效。若非本人操作，请忽略此邮件。");
            mailSender.send(message);
            redis.opsForValue().set("auth:email-code:value:" + suffix, code, CODE_TTL);
            redis.delete("auth:email-code:attempts:" + suffix);
        } catch (MailException e) {
            redis.delete(throttleKey);
            throw new BusinessException(503, "验证码邮件发送失败，请稍后重试");
        }
    }

    public void verify(String email, String code) {
        String suffix = keySuffix(email.trim().toLowerCase());
        String codeKey = "auth:email-code:value:" + suffix;
        String attemptsKey = "auth:email-code:attempts:" + suffix;
        String savedCode = redis.opsForValue().get(codeKey);
        if (savedCode == null) {
            throw new BusinessException(400, "验证码已过期或尚未发送");
        }
        if (!savedCode.equals(code)) {
            Long attempts = redis.opsForValue().increment(attemptsKey);
            if (attempts != null && attempts == 1) {
                redis.expire(attemptsKey, CODE_TTL);
            }
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                redis.delete(codeKey);
                redis.delete(attemptsKey);
            }
            throw new BusinessException(400, "邮箱验证码错误");
        }
    }

    public void consume(String email) {
        String suffix = keySuffix(email.trim().toLowerCase());
        redis.delete("auth:email-code:value:" + suffix);
        redis.delete("auth:email-code:attempts:" + suffix);
        redis.delete("auth:email-code:throttle:" + suffix);
    }

    private String keySuffix(String email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
