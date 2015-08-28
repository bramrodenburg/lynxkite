// Benchmark for a large histogram calculation.
project = lynx.project('random')
project.newVertexSet(size: 10000000)
project.addGaussianVertexAttribute(name: 'random', seed: 1571682864)
project.aggregateVertexAttributeGlobally(prefix: '', 'aggregate-random': 'average')

println "vertex_count: ${ project.scalars['vertex_count'] }"
start_time = System.currentTimeMillis()
println "histogram: ${ project.vertexAttributes['random'].histogram() }"
println "time: ${ (System.currentTimeMillis() - start_time) / 1000 } seconds"
