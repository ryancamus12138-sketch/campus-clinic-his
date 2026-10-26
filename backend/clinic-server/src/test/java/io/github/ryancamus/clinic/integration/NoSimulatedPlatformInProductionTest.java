package io.github.ryancamus.clinic.integration;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient;
import io.github.ryancamus.clinic.finance.invoice.FiscalInvoiceClient;
import io.github.ryancamus.clinic.pharmacy.trace.TraceCodeUploader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式程序（src/main）里不能有外部平台的测试替身：替身只能放在测试代码里。
 * <ul>
 *   <li>正式代码中没有名为 Mock / Fake / Stub 开头、或位于 mock 包下的类；</li>
 *   <li>外部平台接口在正式代码中的实现，只能是登记在 {@link #REAL_ADAPTERS} 里、已与对方测试环境跑通契约测试的真实适配器。
 *       目前一个都没有接入，所以清单为空——部署后这些平台在界面上显示“未接入”或转人工登记。</li>
 * </ul>
 */
class NoSimulatedPlatformInProductionTest {

    /** 已接入的真实适配器（类全名）。接入一个平台时，实现接口、跑通契约测试后登记在这里 */
    private static final Set<String> REAL_ADAPTERS = Set.of();

    private static final List<Class<?>> PORTS = List.of(PayChannelClient.class, CampusCardGateway.class,
            InsuranceGateway.class, FiscalInvoiceClient.class, TraceCodeUploader.class);

    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.ryancamus.clinic");

    @Test
    void noMockClassesInProductionCode() {
        noClasses().should().haveSimpleNameStartingWith("Mock")
                .orShould().haveSimpleNameStartingWith("Fake")
                .orShould().haveSimpleNameStartingWith("Stub")
                .orShould().resideInAPackage("..mock..")
                .because("测试替身只能放在测试代码里，正式程序不能返回虚构的外部平台结果")
                .check(main);
    }

    @Test
    void everyPlatformImplementationInProductionIsARegisteredRealAdapter() {
        for (Class<?> port : PORTS) {
            List<String> impls = main.stream()
                    .filter(c -> !c.isInterface() && c.isAssignableTo(port))
                    .map(JavaClass::getName)
                    .toList();
            assertThat(impls).as("%s 在正式代码中的实现", port.getSimpleName()).isSubsetOf(REAL_ADAPTERS);
        }
    }
}
