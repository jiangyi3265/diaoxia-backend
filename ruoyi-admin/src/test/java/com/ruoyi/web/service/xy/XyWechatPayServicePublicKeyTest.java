package com.ruoyi.web.service.xy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.web.config.XyWechatPayProperties;

class XyWechatPayServicePublicKeyTest
{
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_1116741825";
    private static final String API_V3_KEY = "1234567890abcdef1234567890abcdef";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair wechatPayKeyPair;
    private XyWechatPayProperties properties;
    private XyWechatPayService service;

    @BeforeEach
    void setUp() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        wechatPayKeyPair = generator.generateKeyPair();

        Path publicKeyPath = tempDir.resolve("wechatpay_public_key.pem");
        String publicKey = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(wechatPayKeyPair.getPublic().getEncoded());
        Files.write(publicKeyPath, ("-----BEGIN PUBLIC KEY-----\n" + publicKey
                + "\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.UTF_8));
        Path merchantPrivateKey = tempDir.resolve("apiclient_key.pem");
        String privateKey = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(wechatPayKeyPair.getPrivate().getEncoded());
        Files.write(merchantPrivateKey, ("-----BEGIN PRIVATE KEY-----\n" + privateKey
                + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.UTF_8));

        properties = new XyWechatPayProperties();
        properties.setMchId("1116741825");
        properties.setMerchantSerialNo("1774CC5F");
        properties.setPrivateKeyPath(merchantPrivateKey.toString());
        properties.setNotifyUrl("https://api.example.com/app/payments/wechat/notify");
        properties.setRefundNotifyUrl("https://api.example.com/app/payments/wechat/refund-notify");
        properties.setAppId("wx62712d7f9c391049");
        properties.setApiV3Key(API_V3_KEY);
        properties.setPublicKeyId(PUBLIC_KEY_ID);
        properties.setPublicKeyPath(publicKeyPath.toString());
        properties.setPlatformCertificatePath(tempDir.resolve("missing-platform-cert.pem").toString());
        service = new XyWechatPayService(properties);
    }

    @Test
    void signsTheCompleteJsapiPackageValue() throws Exception
    {
        String prepayId = "wx-test-prepay-id";
        Map<String, Object> payment = service.buildJsapiPaymentParameters(prepayId);
        assertEquals("prepay_id=" + prepayId, payment.get("package"));

        String message = properties.getAppId() + "\n" + payment.get("timeStamp") + "\n"
                + payment.get("nonceStr") + "\n" + payment.get("package") + "\n";
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(wechatPayKeyPair.getPublic());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(String.valueOf(payment.get("paySign")))));

        String incompleteMessage = properties.getAppId() + "\n" + payment.get("timeStamp") + "\n"
                + payment.get("nonceStr") + "\n" + prepayId + "\n";
        verifier.initVerify(wechatPayKeyPair.getPublic());
        verifier.update(incompleteMessage.getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(Base64.getDecoder().decode(String.valueOf(payment.get("paySign")))));
    }

    @Test
    void acceptsCompleteWechatPayPublicKeyConfiguration()
    {
        assertTrue(service.isWechatPayConfigured());
    }

    @Test
    void includesTheSeatHoldDeadlineInWechatPrepayRequest()
    {
        Map<String, Object> body = service.buildJsapiRequestBody("FP20260901", "openid", 10000,
                "福利钓专场", LocalDateTime.of(2026, 9, 1, 12, 34, 56));
        assertEquals("2026-09-01T12:34:56+08:00", body.get("time_expire"));
        assertEquals(10000, ((Map<?, ?>) body.get("amount")).get("total"));
    }

    @Test
    void verifiesAndDecryptsCallbackWithMatchingPublicKeyId() throws Exception
    {
        String resourceNonce = "0123456789ab";
        String associatedData = "transaction";
        String plaintext = "{\"mchid\":\"1116741825\",\"appid\":\"wx62712d7f9c391049\"}";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, resourceNonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("algorithm", "AEAD_AES_256_GCM");
        resource.put("nonce", resourceNonce);
        resource.put("associated_data", associatedData);
        resource.put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("resource", resource);
        String body = mapper.writeValueAsString(root);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "callback-nonce";
        String signature = sign(timestamp + "\n" + nonce + "\n" + body + "\n");

        Map<?, ?> result = service.verifyCallback(timestamp, nonce, signature, PUBLIC_KEY_ID, body);
        assertEquals("1116741825", result.get("mchid"));
        assertThrows(ServiceException.class,
                () -> service.verifyCallback(timestamp, nonce, signature, "PUB_KEY_ID_WRONG", body));
    }

    private String sign(String message) throws Exception
    {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(wechatPayKeyPair.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
