package com.delightroom.mission.core.testfixture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.GeneralCodingRules
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility

/**
 * 계층 · 의존 규칙. 각 앱의 테스트가 자기 클래스패스(공통 · core · infra · 그 앱)를 넣어 검사한다.
 *
 * 의존 방향: app → core · infra, infra → core, core → common.
 */
object ArchitectureRules {
    private const val ROOT = "com.delightroom.mission"
    private const val COMMON = "$ROOT.common.."
    private const val CORE = "$ROOT.core.."
    private const val DOMAIN = "$ROOT.core.domain.."
    private const val PORT = "$ROOT.core.port.."
    private const val SERVICE = "$ROOT.core.service.."
    private const val CONFIG = "$ROOT.core.config.."
    private const val INFRA = "$ROOT.infra.."
    private const val APP = "$ROOT.app.."
    private const val API = "$ROOT.app.api.."
    private const val REALTIME = "$ROOT.app.realtime.."

    fun importProductionClasses(): JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .withImportOption { location -> !location.contains("test-fixtures") && !location.contains("testFixtures") }
            .importPackages(ROOT)

    val commonHasNoFrameworkOrInnerLayer: ArchRule =
        noClasses().that().resideInAPackage(COMMON)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", CORE, INFRA, APP)
            .because("support/common 은 Spring 의존이 없는 최하위 모듈이다")

    val coreDoesNotDependOnInfraOrApp: ArchRule =
        noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(INFRA, APP)

    val infraDoesNotDependOnApp: ArchRule =
        noClasses().that().resideInAPackage(INFRA)
            .should().dependOnClassesThat().resideInAPackage(APP)

    val appDoesNotUseInfraDirectly: ArchRule =
        noClasses().that().resideInAPackage(APP)
            .should().dependOnClassesThat().resideInAPackage(INFRA)
            .because("앱은 core 의 포트로만 저장소를 쓴다(DIP) — 구현은 자동 구성이 넣는다")
            .allowEmptyShould(true)

    val appsAreIndependent: List<ArchRule> = listOf(
        noClasses().that().resideInAPackage(API).should().dependOnClassesThat().resideInAPackage(REALTIME)
            .allowEmptyShould(true),
        noClasses().that().resideInAPackage(REALTIME).should().dependOnClassesThat().resideInAPackage(API)
            .allowEmptyShould(true),
    )

    val domainModelHasNoFramework: ArchRule =
        noClasses().that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .because("도메인 모델에 Spring · JPA 를 붙이지 않는다")

    val servicesDependOnlyOnCorePorts: ArchRule =
        classes().that().resideInAPackage(SERVICE)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                COMMON, CORE, "java..", "kotlin..", "kotlinx.coroutines..", "org.jetbrains.annotations..",
                "org.springframework.stereotype..",
            )
            .because("Service 는 core 의 인터페이스에만 의존한다(DIP)")

    val portsAreInterfaces: ArchRule =
        classes().that().resideInAPackage(PORT).should().beInterfaces()

    val infraImplementationsAreInternal: ArchRule =
        classes().that().resideInAPackage(INFRA)
            .and().implement(DescribedPredicate.describe("core 포트") { it.packageName.startsWith("$ROOT.core.port") })
            .should(beKotlinInternal())
            .because("포트 구현은 infra 안에 숨기고 @AutoConfiguration 으로만 등록한다")
            .allowEmptyShould(true)

    val dtosLiveInApp: ArchRule =
        classes().that().haveSimpleNameEndingWith("Request")
            .or().haveSimpleNameEndingWith("Response")
            .or().haveSimpleNameEndingWith("Dto")
            .should().resideInAPackage(APP)
            .because("DTO 는 app 모듈에만 둔다")
            .allowEmptyShould(true)

    val constructorInjectionOnly: List<ArchRule> = listOf(
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION,
        noMethods().should().beAnnotatedWith(AUTOWIRED)
            .because("생성자 주입만 쓴다").allowEmptyShould(true),
    )

    val timeComesFromClockBean: List<ArchRule> = listOf(
        noClasses().should().callMethodWhere(parameterlessNow())
            .because("시간은 Clock 빈으로만 얻는다 — now() 는 Clock 을 받는 오버로드만 쓴다"),
        noClasses().should().callMethod(System::class.java, "currentTimeMillis"),
        noClasses().that().resideOutsideOfPackage(CONFIG)
            .should().callMethodWhere(systemClockFactory())
            .because("시스템 Clock 은 CoreConfiguration 의 빈 하나뿐이다"),
    )

    val all: List<ArchRule> = buildList {
        add(commonHasNoFrameworkOrInnerLayer)
        add(coreDoesNotDependOnInfraOrApp)
        add(infraDoesNotDependOnApp)
        add(appDoesNotUseInfraDirectly)
        addAll(appsAreIndependent)
        add(domainModelHasNoFramework)
        add(servicesDependOnlyOnCorePorts)
        add(portsAreInterfaces)
        add(infraImplementationsAreInternal)
        add(dtosLiveInApp)
        addAll(constructorInjectionOnly)
        addAll(timeComesFromClockBean)
    }

    private const val AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired"

    private val TIME_TYPES = setOf(
        "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
        "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.Year", "java.time.YearMonth",
    )

    private fun parameterlessNow() =
        DescribedPredicate.describe<JavaMethodCall>("인자 없는 java.time *.now()") { call ->
            call.target.name == "now" &&
                call.target.owner.name in TIME_TYPES &&
                call.target.rawParameterTypes.none { it.name == "java.time.Clock" }
        }

    private fun systemClockFactory() =
        DescribedPredicate.describe<JavaMethodCall>("Clock.system*()") { call ->
            call.target.owner.name == "java.time.Clock" && call.target.name.startsWith("system")
        }

    private fun beKotlinInternal(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("be Kotlin internal") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val metadata = item.reflect().getAnnotation(Metadata::class.java)
                val visibility = (metadata?.let { KotlinClassMetadata.readLenient(it) } as? KotlinClassMetadata.Class)
                    ?.kmClass?.visibility
                val ok = visibility == Visibility.INTERNAL
                events.add(SimpleConditionEvent(item, ok, "${item.name} 의 가시성은 $visibility"))
            }
        }
}
