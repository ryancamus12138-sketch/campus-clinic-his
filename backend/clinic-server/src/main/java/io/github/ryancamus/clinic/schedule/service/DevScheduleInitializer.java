package io.github.ryancamus.clinic.schedule.service;

import io.github.ryancamus.clinic.schedule.domain.dto.SlotBatchCreateDTO;
import io.github.ryancamus.clinic.schedule.enums.SlotType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 开发环境初始化：为开发环境演示数据补齐未来 7 天的门诊、体检、疫苗号源（已存在的跳过）。
 * 号源与日期相关，不适合写死在 SQL 演示数据里。
 * <p>
 * 只在 {@code dev} Profile 下注册（开发与演示编排、截图流程）；测试（{@code test}）和生产（{@code prod}）都不会执行，
 * 生产环境的号源由管理员在“号源与排班”中维护。
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevScheduleInitializer implements ApplicationRunner {

    /** 开发环境演示数据中的校区、科室、医生（见 V2 参考数据与 seed-dev 演示账号） */
    private static final long DEMO_CAMPUS = 1L;
    private static final long DEPT_INTERNAL = 101L;
    private static final long DEPT_SURGERY = 102L;
    private static final long DEPT_EXAM = 104L;
    private static final long DEPT_VACCINE = 105L;
    private static final long DEMO_DOCTOR = 4L;

    private final SlotService slotService;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> week = IntStream.range(0, 7).mapToObj(today::plusDays).toList();
        int created = 0;
        created += slotService.batchCreate(new SlotBatchCreateDTO(DEMO_CAMPUS, DEPT_INTERNAL, DEMO_DOCTOR,
                SlotType.OUTPATIENT.code(), week, halfHourPeriods(8, 17, 6), "GH-PT", null));
        created += slotService.batchCreate(new SlotBatchCreateDTO(DEMO_CAMPUS, DEPT_SURGERY, 0L,
                SlotType.OUTPATIENT.code(), week, halfHourPeriods(8, 12, 4), "GH-PT", null));
        created += slotService.batchCreate(new SlotBatchCreateDTO(DEMO_CAMPUS, DEPT_EXAM, 0L,
                SlotType.PHYSICAL_EXAM.code(), week, halfHourPeriods(8, 11, 120), "TJ-XS", null));
        created += slotService.batchCreate(new SlotBatchCreateDTO(DEMO_CAMPUS, DEPT_VACCINE, 0L,
                SlotType.VACCINE.code(), week, List.of(
                new SlotBatchCreateDTO.Period(LocalTime.of(9, 0), LocalTime.of(11, 0), 50),
                new SlotBatchCreateDTO.Period(LocalTime.of(14, 0), LocalTime.of(16, 0), 50)), "YM-FW", null));
        if (created > 0) {
            log.info("开发环境初始化：已生成演示号源 {} 个", created);
        }
    }

    private static List<SlotBatchCreateDTO.Period> halfHourPeriods(int fromHour, int toHour, int perSlot) {
        return IntStream.range(fromHour * 2, toHour * 2)
                .filter(i -> i != 23 && i != 24 && i != 25) // 11:30-13:00 午休
                .mapToObj(i -> new SlotBatchCreateDTO.Period(
                        LocalTime.of(i / 2, (i % 2) * 30), LocalTime.of((i + 1) / 2, ((i + 1) % 2) * 30), perSlot))
                .toList();
    }
}
