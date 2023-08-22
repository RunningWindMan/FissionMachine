package com.devil.fission.machine.auth.service.service;

import com.devil.fission.machine.auth.api.dto.VerifySignDto;
import com.devil.fission.machine.auth.api.enums.AccessSourceEnum;
import com.devil.fission.machine.auth.service.config.AccessProperties;
import com.devil.fission.machine.common.response.Response;
import com.devil.fission.machine.common.response.ResponseCode;
import com.devil.fission.machine.common.util.CollectionUtils;
import com.devil.fission.machine.common.util.StringUtils;
import com.devil.fission.machine.redis.service.RedisService;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SignService.
 *
 * @author devil
 * @date Created in 2023/5/4 13:53
 */
@Service
public class SignService {
    
    private final RedisService redisService;
    
    private final AccessProperties accessProperties;
    
    public SignService(RedisService redisService, AccessProperties accessProperties) {
        this.redisService = redisService;
        this.accessProperties = accessProperties;
    }
    
    public Response<VerifySignDto> verifySign(String accessKey, String timestamp, String nonce, String requestSign, String requestUri) {
        String nonceFormatPrefix = "FISSION-MACHINE-API-NONCE:%s";
        VerifySignDto verifySignDto = VerifySignDto.builder().build();
        if (Objects.nonNull(redisService.getAsString(String.format(nonceFormatPrefix, nonce)))) {
            return Response.other(ResponseCode.UN_AUTHORIZED, "标签nonce重复", verifySignDto);
        }
        
        // 校验标签
        TreeMap<String, String> treeMap = new TreeMap<>();
        treeMap.put("accessKey", accessKey);
        treeMap.put("nonce", nonce);
        treeMap.put("timestamp", timestamp);
        String argStr = treeMap.entrySet().stream().map(Object::toString).collect(Collectors.joining("&"));
        // 通过accessKey获取accessSecret
        Map<String, AccessProperties.Access> accessMap = accessProperties.getAccessMap();
        AccessProperties.Access access = accessMap.get(accessKey);
        if (access == null || StringUtils.isEmpty(access.getAccessSecret())) {
            return Response.other(ResponseCode.UN_AUTHORIZED, "未授权", verifySignDto);
        }
        String accessSecret = access.getAccessSecret();
        String secretStr = argStr + "&accessSecret=" + accessSecret;
        String serverSign = DigestUtils.md5DigestAsHex(secretStr.getBytes(StandardCharsets.UTF_8)).toUpperCase(Locale.ROOT);
        if (!Objects.equals(serverSign, requestSign)) {
            return Response.other(ResponseCode.UN_AUTHORIZED, "签名错误", verifySignDto);
        }
        
        // 特殊校验uri
        if (CollectionUtils.isNotEmpty(access.getAccessUriList())) {
            boolean accessed = false;
            for (String uri : access.getAccessUriList()) {
                if (requestUri.startsWith(uri)) {
                    accessed = true;
                    break;
                }
            }
            if (!accessed) {
                return Response.other(ResponseCode.UN_AUTHORIZED, "访问url：" + requestUri + " 未授权", verifySignDto);
            }
        }
        
        String accessSource = access.getAccessSource();
        AccessSourceEnum apiSourceEnum = AccessSourceEnum.getByValue(accessSource);
        if (apiSourceEnum.equals(AccessSourceEnum.NONE)) {
            return Response.other(ResponseCode.UN_AUTHORIZED, "访问来源未授权", verifySignDto);
        }
        verifySignDto.setAccessSource(apiSourceEnum);
        
        // 校验通过塞入nonce，60s内防止重复提交
        redisService.setStringWithEx(String.format(nonceFormatPrefix, nonce), nonce, 60L, TimeUnit.SECONDS);
        return Response.success(verifySignDto);
    }
    
}
