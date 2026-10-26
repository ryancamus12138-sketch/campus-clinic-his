package io.github.ryancamus.clinic;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用测试守住分层与模块边界，违反即 CI 失败，而不是只写在文档里。
 * <p>
 * 使用普通 JUnit 测试方法调用规则（而非 @ArchTest 字段），确保 Surefire 一定执行并计入测试数。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.ryancamus.clinic");
    }

    @Test
    void importsProductionClassesFromAllModules() {
        // 防止导入为空导致规则“空转通过”
        assertThat(classes.containPackage("io.github.ryancamus.clinic.common.money")).isTrue();
        assertThat(classes.containPackage("io.github.ryancamus.clinic.framework.security")).isTrue();
        assertThat(classes.containPackage("io.github.ryancamus.clinic.system.controller")).isTrue();
    }

    @Test
    void controllersDoNotTouchMappers() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..mapper..")
                .because("Controller 只做参数校验与转换，数据访问必须经过 Service")
                .check(classes);
    }

    @Test
    void controllersDoNotExposeEntities() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..domain.entity..")
                .because("实体只在 Service/Mapper 内流转，接口出入参使用 DTO/VO")
                .check(classes);
    }

    @Test
    void mappersAreOnlyUsedByServices() {
        classes().that().resideInAPackage("..mapper..")
                .should().onlyBeAccessed().byAnyPackage("..service..", "..mapper..")
                .because("跨模块不得直接访问对方的 Mapper")
                .check(classes);
    }

    @Test
    void commonDoesNotDependOnFrameworks() {
        noClasses().that().resideInAPackage("..clinic.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "..clinic.framework..", "com.baomidou..")
                .check(classes);
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching("io.github.ryancamus.clinic.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }
}
