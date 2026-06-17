plugins {
    id("acme.java-conventions")
}

dependencies {
    // jMolecules DDD stereotypes (architecturally evident code) — versions via the jMolecules BOM.
    "implementation"(platform("org.jmolecules:jmolecules-bom:2025.0.2"))
    "implementation"("org.jmolecules:jmolecules-ddd")
    "implementation"("org.jmolecules:jmolecules-events")

    // Architecture fitness functions.
    "testImplementation"(platform("org.jmolecules:jmolecules-bom:2025.0.2"))
    "testImplementation"("com.tngtech.archunit:archunit-junit5:1.4.2")
    "testImplementation"("org.jmolecules.integrations:jmolecules-archunit")
}
