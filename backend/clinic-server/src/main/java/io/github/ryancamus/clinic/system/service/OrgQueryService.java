package io.github.ryancamus.clinic.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import io.github.ryancamus.clinic.system.domain.entity.ChargePoint;
import io.github.ryancamus.clinic.system.domain.entity.SysDept;
import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import io.github.ryancamus.clinic.system.mapper.ChargePointMapper;
import io.github.ryancamus.clinic.system.mapper.SysDeptMapper;
import io.github.ryancamus.clinic.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 科室、员工名称变化很少，用本地缓存（5 分钟）避免列表页每次都查库。
 */
@Service
@RequiredArgsConstructor
public class OrgQueryService implements OrgQueryApi {

    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final ChargePointMapper chargePointMapper;

    private final Cache<Long, String> deptNameCache = Caffeine.newBuilder()
            .maximumSize(1_000).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<Long, String> nicknameCache = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    @Override
    public Map<Long, String> deptNames(Collection<Long> deptIds) {
        return cachedLookup(deptIds, deptNameCache, missing -> deptMapper.selectByIds(missing).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName)));
    }

    @Override
    public Map<Long, String> userNicknames(Collection<Long> userIds) {
        return cachedLookup(userIds, nicknameCache, missing -> userMapper.selectByIds(missing).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname)));
    }

    @Override
    public boolean deptExists(Long campusId, Long deptId) {
        return deptMapper.exists(Wrappers.<SysDept>lambdaQuery()
                .eq(SysDept::getId, deptId)
                .eq(SysDept::getCampusId, campusId)
                .eq(SysDept::getStatus, 1));
    }

    @Override
    public Optional<ChargePointInfo> defaultChargePoint(Long campusId) {
        return chargePointMapper.selectList(Wrappers.<ChargePoint>lambdaQuery()
                        .eq(ChargePoint::getCampusId, campusId)
                        .eq(ChargePoint::getStatus, 1)
                        .orderByAsc(ChargePoint::getSort, ChargePoint::getId)
                        .last("LIMIT 1"))
                .stream().findFirst()
                .map(c -> new ChargePointInfo(c.getId(), c.getName(), ChargePointService.methods(c)));
    }

    @Override
    public Map<Long, String> chargePointNames(Collection<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return chargePointMapper.selectByIds(wanted).stream()
                .collect(Collectors.toMap(ChargePoint::getId, ChargePoint::getName));
    }

    private static Map<Long, String> cachedLookup(Collection<Long> ids, Cache<Long, String> cache,
                                                  Function<Set<Long>, Map<Long, String>> loader) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).filter(id -> id > 0).collect(Collectors.toSet());
        Map<Long, String> result = new HashMap<>(cache.getAllPresent(wanted));
        Set<Long> missing = wanted.stream().filter(id -> !result.containsKey(id)).collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            Map<Long, String> loaded = loader.apply(missing);
            cache.putAll(loaded);
            result.putAll(loaded);
        }
        return result;
    }
}
