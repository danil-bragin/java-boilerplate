package com.acme.bank.antifraud;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.acme.bank.antifraud");

    @Test
    void domainHasNoSpringOrPersistenceDependencies() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.acme.persistence..",
                        "com.acme.bank.contracts.avro..");
        rule.check(classes);
    }

    @Test
    void domainDoesNotDependOnAdapters() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @Test
    void applicationDoesNotDependOnAdapters() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }
}
