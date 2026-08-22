rootProject.name = "minestom"

includeBuild("build-src")

include("code-generators")
include("testing")
include("bedrock")

include("jmh-benchmarks")
include("jcstress-tests")

include("demo")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
