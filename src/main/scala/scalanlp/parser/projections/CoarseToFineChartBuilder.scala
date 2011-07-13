package scalanlp.parser
package projections



import CoarseToFineChartBuilder._;

@SerialVersionUID(1)
class CoarseToFineChartBuilder[Chart[X]<:ParseChart[X],C,F,W](coarseParser: ChartBuilder[Chart,C,W],
                                proj: F=>C,
                                val root: F,
                                val lexicon: Lexicon[F,W],
                                val grammar: Grammar[F],
                                chartFactory: ParseChart.Factory[Chart] = ParseChart.viterbi,
                                threshold:Double = -10) extends ChartBuilder[Chart,F,W] with Serializable {

  val indexedProjections = ProjectionIndexer(coarseParser.grammar.labelIndex, grammar.labelIndex, proj);

  private val coarseRootIndex = coarseParser.grammar.labelIndex(proj(root));

  private val fineParser = new CKYChartBuilder[Chart,F,W](root,lexicon,grammar,chartFactory);

  def buildInsideChart(s: Seq[W], validSpan: SpanScorer[F] = SpanScorer.identity[F]):Chart[F] = {
    val chartScorer = coarseSpanScorerFromParser[W,C,F,Chart](s, coarseParser, indexedProjections, threshold);
    val myScorer = SpanScorer.sum(chartScorer,validSpan);
    fineParser.buildInsideChart(s, myScorer);
  }


  /**
   * Given an inside chart, fills the passed-in outside parse chart with inside scores.
   */
  def buildOutsideChart(inside: ParseChart[F],
                        validSpan: SpanScorer[F] = SpanScorer.identity):Chart[F] = {
    fineParser.buildOutsideChart(inside, validSpan);
  }

  def withCharts[Chart[X]<:ParseChart[X]](factory: ParseChart.Factory[Chart]) = {
    val cc = coarseParser.withCharts(factory);
    new CoarseToFineChartBuilder[Chart,C,F,W](cc,proj, root,lexicon,grammar,factory, threshold);
  }
}

object CoarseToFineChartBuilder {
  def coarseChartSpanScorer[C,F](proj: Int=>Int,
                               coarseInside: ParseChart[C],
                               coarseOutside:ParseChart[C],
                               sentProb:Double,
                               threshold:Double = -10):SpanScorer[F] = new SpanScorer[F] {

    @inline
    def score(begin: Int, end: Int, label: Int) = {
      val score =  (coarseInside.bot.labelScore(begin,end,proj(label))
              + coarseOutside.bot.labelScore(begin,end,proj(label)) - sentProb);
      if (score > threshold) 0.0 else Double.NegativeInfinity;
    }

    def scoreUnaryRule(begin: Int, end: Int, rule: Int) = 0.0;


    def scoreBinaryRule(begin: Int, split: Int, end: Int, rule: Int) = {
      0.0
    }

    def scoreSpan(begin: Int, end: Int, tag: Int): Double = {
      score(begin,end,tag);
    }
  }

  def coarseSpanScorerFromParser[W,C,F,Chart[X]<:ParseChart[X]](s: Seq[W],
                                                              coarseParser: ChartBuilder[Chart,C,W],
                                                              proj: Int=>Int,
                                                              threshold: Double = -10):SpanScorer[F] = {
    val coarseRootIndex = coarseParser.grammar.labelIndex(coarseParser.root);
    val coarseInside = coarseParser.buildInsideChart(s)
    val coarseOutside = coarseParser.buildOutsideChart(coarseInside);

    val sentProb = coarseInside.top.labelScore(0,s.length,coarseRootIndex);
    assert(!sentProb.isInfinite, s);

    val chartScorer = CoarseToFineChartBuilder.coarseChartSpanScorer[C,F](proj,
      coarseInside, coarseOutside, sentProb, threshold);

    chartScorer
  }
}

