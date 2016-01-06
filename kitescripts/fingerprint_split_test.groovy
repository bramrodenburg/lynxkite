// The input graph is expected to be a sort of 'callgraph'.
// It should contain neither loop edges nor multiple edges.
// It should have a vertex attribute 'peripheral' which is 0
// when the vertex has all its neighbors in the graph, and 1
// when the vertex (in the original graph) had some neighbors
// which are not present in the input graph.
// Is should also have an edge attribute 'originalCalls', which is the number of times
// one vertex (user) called another one. This property is used as a weight for the finger
// printing algorithm.


// Parameters
furtherUndefinedAttr1 = params.fa1 ?: '5'
furtherUndefinedAttr2 = params.fa2 ?: '5'
splits = params.splits ?: '10'
input =  params.input ?: 'fprandom'
seed = params.seed ?: '31415'


furtherUndefinedAttr1Expr =
        '(originalUniqueId >= ' +
        splits +
        ' && ' +
        splits +
        ' + ' +
        furtherUndefinedAttr1 +
        ' > originalUniqueId) ? 1.0 : 0.0'


furtherUndefinedAttr2Expr =
        '(originalUniqueId >= ' +
        splits +
        ' + ' +
        furtherUndefinedAttr1 +
        ' && ' +
        splits +
        ' + ' +
        furtherUndefinedAttr1 +
        ' + ' +
        furtherUndefinedAttr2 +
        ' > originalUniqueId) ? 1.0 : 0.0'


split=lynx.newProject('split test for FP')
split.importVerticesFromCSVFiles(
  files: 'DATA$/exports/' + input + '_vertices/data/part*',
  header: '"id","peripheral"',
  delimiter: ',',
  omitted: '',
  filter: '',
  "id-attr": 'newId',
  allow_corrupt_lines: 'no'
)
split.importEdgesForExistingVerticesFromCSVFiles(
  files: 'DATA$/exports/' + input + '_edges/data/part*',
  header: '"src_id","dst_id","originalCalls"',
  delimiter: ',',
  omitted: '',
  filter: '',
  allow_corrupt_lines: 'no',
  attr: 'id',
  src: 'src_id',
  dst: 'dst_id'
)
// Convert strings to doubles:
split.vertexAttributeToDouble(
  attr: 'peripheral'
)
split.edgeAttributeToDouble(
  attr: 'originalCalls'
)

// Create vertex attribute 'originalUniqueId' - this runs beteen 0 and number of vertices - 1
// Low ids will be treated specially, e.g., splits, and further undefined will come from
// the low regions of the id range.
split.addRandomVertexAttribute(
  name: 'urnd',
  dist: 'Standard Uniform',
  seed: seed
)

split.addRankAttribute(
  rankattr: 'originalUniqueId',
  keyattr: 'urnd',
  order: 'ascending'
)

split.derivedVertexAttribute(
  output: 'split',
  expr: '(originalUniqueId < ' + splits + ') ? 2.0 : 1.0',
  type: 'double'
)

// Save split, because we're going to modify it.
split.copyVertexAttribute(
  from: 'split',
  to: 'splitSave'
)

split.derivedVertexAttribute(
  output: 'furtherUndefinedAttr1',
  type: 'double',
  expr: furtherUndefinedAttr1Expr
)

split.derivedVertexAttribute(
  output: 'furtherUndefinedAttr2',
  type: 'double',
  expr: furtherUndefinedAttr2Expr
)

split.vertexAttributeToString(
  attr: 'originalUniqueId'
)

split.splitVertices(
  rep: 'split',
  idattr: 'newId',
  idx: 'index'
)
split.vertexAttributeToDouble(
  attr: 'index'
)


split.derivedVertexAttribute(
  output: 'attr1',
  expr: '(furtherUndefinedAttr1 == 1.0 || (split == 2.0 && index == 0)) ? undefined : originalUniqueId',
  type: 'string'
)

split.derivedVertexAttribute(
  output: 'attr2',
  expr: '(furtherUndefinedAttr2 == 1.0 || (split == 2.0 && index == 1)) ? undefined : originalUniqueId',
  type: 'string'
)


split.derivedEdgeAttribute(
  output: 'splitCalls',
  type: 'double',
  expr:
  """
  function Rnd(seedFirst, seedSecond) {
    var seed = util.hash(seedFirst.toString() + '_' + seedSecond.toString());
    var rnd = util.rnd(seed);
    return {
      next: function() {
        return rnd.nextDouble();
      },
    }
  }

  var srcSeed = src\$originalUniqueId
  var dstSeed =  dst\$originalUniqueId
  var srcCount = src\$split;
  var dstCount = dst\$split;
  var srcIdx = src\$index;
  var dstIdx = dst\$index;
  var edgeCnt = originalCalls

  var total = srcCount * dstCount;
  var myId = dstCount * srcIdx + dstIdx;

  function splitCalls() {
    // First, let's consider some cases when it's possible
    // to tell the return value without actually
    // computing edgeCnt random numbers.

    // 0) Pathalogical case: we're invoked from from validateJS
    if (total === 0) {
      return 0;
    }

    // 1) Simplest case: neither the source, nor the destination is
    // split: total === 1 and myId === 0. There is only one
    // edge and it will carry the original count.
    if (total === 1) {
      return edgeCnt;
    }

    // 2) The next simplest case occurs when either the source,
    // or the destination is split, but not both. Here, total === 2
    // and myId falls between 0 and 1 inclusive.
    // We'll need to split edgeCnt between the two edges. However, we cannot
    // avoid generating all edgeCnt random numbers, so the computation
    // must continue.


    // 3) In the most complex case, both the source and the destination
    // vertices are split, resulting in 4 edges. However, we want to
    // devide the edgeCnt quantity between the first edge (myId: 0) and the last
    // one (myId: 3). The two other edges get 0.
    if (total === 4 && (myId === 1 || myId === 2)) {
      return 0;
    }

    var rnd = Rnd(srcSeed, dstSeed)

    var countForTheFirstEdge = 0;
    for (var j = 0; j < edgeCnt; j++) {
      if (rnd.next() < 0.5) {
        countForTheFirstEdge++;
      }
    }
    var countForTheLastEdge = edgeCnt - countForTheFirstEdge;

    var thisIsTheFirstEdge = myId === 0;

    if (thisIsTheFirstEdge) {
      return countForTheFirstEdge;
    } else {
      return countForTheLastEdge;
    }
  }

  splitCalls();
  """
)


split.filterByAttributes(
'filterea-splitCalls': '> 0.0',
)


// Do fingerprinting
split.fingerprintingBasedOnAttributes(
  leftName: 'attr1',
  rightName: 'attr2',
  weights: 'splitCalls',
  mo: '2',
  ms: '0.0'
)


split.fillWithConstantDefaultValue(
  attr: 'attr1',
  def: '-1'
)

split.fillWithConstantDefaultValue(
  attr: 'attr2',
  def: '-1'
)


split.derivedVertexAttribute(
  output: 'label',
  type: 'string',
  expr: 'originalUniqueId + "," + attr1 + "," + attr2'
)


split.derivedVertexAttribute(
  output: 'normal',
  type: 'double',
  expr: '(split == 1.0 && furtherUndefinedAttr1 == 0.0 && furtherUndefinedAttr2 == 0.0) ? 1.0 : 0.0'
)

split.derivedVertexAttribute(
  output: 'furtherOk',
  type: 'double',
  expr: '((furtherUndefinedAttr1 == 1.0 && attr1 == -1) || (furtherUndefinedAttr2 == 1.0 && attr2 == -1)) ? 1.0 : 0.0'
)

split.derivedVertexAttribute(
  output: 'furtherBad',
  type: 'double',
  expr: '((furtherUndefinedAttr1 == 1.0 && attr1 != -1) || (furtherUndefinedAttr2 == 1.0 && attr2 != -1)) ? 1.0 : 0.0'
)

split.derivedVertexAttribute(
  output: 'churnerFound',
  type: 'double',
  expr: '(split == 2.0 && attr1 == attr2) ? 1.0 : 0.0'
)

split.derivedVertexAttribute(
  output: 'churnerNoMatch',
  type: 'double',
  expr: '(split == 2.0 && (attr1 == -1 || attr2 == -1)) ? 1.0 : 0.0'
)

split.derivedVertexAttribute(
  output: 'churnerMisMatch',
  type: 'double',
  expr: '(split == 2.0 && attr1 != -1 && attr2 != -1 && attr2 != attr1) ? 1.0 : 0.0'
)

split.derivedVertexAttribute(
  output: 'labelType',
  type: 'string',
  expr:

  """
  var tmp = "";
  tmp += normal == 1.0 ? "normal" : "";
  tmp += furtherOk == 1.0 ? "furtherOk" : "";
  tmp += furtherBad == 1.0 ? "furtherBad" : "";
  tmp += churnerFound == 1.0 ? "churnerFound" : "";
  tmp += churnerNoMatch == 1.0 ? "churnerNoMatch" : "";
  tmp += churnerMisMatch == 1.0 ? "churnerMisMatch" : "";
  tmp;
  """
)


split.aggregateVertexAttributeGlobally(
  prefix: "",
  "aggregate-normal": "sum"
)

split.aggregateVertexAttributeGlobally(
  prefix: "",
  "aggregate-furtherOk": "sum"
)

split.aggregateVertexAttributeGlobally(
  prefix: "",
  "aggregate-furtherBad": "sum"
)

split.aggregateVertexAttributeGlobally(
  prefix: "",
  "aggregate-churnerFound": "sum"
)

split.aggregateVertexAttributeGlobally(
  prefix: "",
  "aggregate-churnerNoMatch": "sum"
)

split.aggregateVertexAttributeGlobally(
  prefix: "",
  "aggregate-churnerMisMatch": "sum"
)

vertices=split.scalars['vertex_count']
edges=split.scalars['edge_count']
normal=split.scalars['normal_sum']
furtherOk=split.scalars['furtherOk_sum']
furtherBad=split.scalars['furtherBad_sum']
churnerFound =split.scalars['churnerFound_sum']
churnerNoMatch =split.scalars['churnerNoMatch_sum']
churnerMisMatch =split.scalars['churnerMisMatch_sum']

println "vertices: $vertices"
println "edges: $edges"

println "normal $normal"
println "furtherOk $furtherOk"
println "furtherBad $furtherBad"
println "churnerFound $churnerFound"
println "churnerNoMatch $churnerNoMatch"
println "churnerMisMatch $churnerMisMatch"
