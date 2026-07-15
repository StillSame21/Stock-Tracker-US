package com.stocktracker;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Step 1 acceptance criterion: zero Alpaca types are importable from outside
 * the {@code gateway} package.
 */
@AnalyzeClasses(packages = "com.stocktracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule alpaca_types_stay_inside_gateway_package =
            noClasses().that().resideOutsideOfPackage("com.stocktracker.gateway..")
                    .should().dependOnClassesThat().resideInAPackage("com.stocktracker.gateway.alpaca..");

    @ArchTest
    static final ArchRule only_gateway_talks_to_spring_web_client =
            noClasses().that().resideOutsideOfPackage("com.stocktracker.gateway..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.web.client..");
}
