package epic.parser
/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

import nak.inference.Factor
import epic.lexicon.Lexicon
import epic.constraints.{TagConstraints, ChartConstraints}
import breeze.numerics.logI

/**
 * [[epic.parser.CoreAnchoring]] score rules and labels in a particular context
 * without needed extra "refined" categories. That is, an anchoring can
 * score x-bar spans in a particular context.
 *
 * @author dlwh
 */
@SerialVersionUID(1)
trait CoreAnchoring[L, W] extends Factor[CoreAnchoring[L, W]] {
  def topology: RuleTopology[L]
  def lexicon: Lexicon[L, W]
  def words: IndexedSeq[W]

  private lazy val lexLoc = lexicon.anchor(words)

  def tagConstraints: TagConstraints[L] = lexLoc
  def sparsityPattern = ChartConstraints.noSparsity[L]
  def addConstraints(cs: ChartConstraints[L]):CoreAnchoring[L, W]

  /**
   * Scores the indexed [[epic.trees.BinaryRule]] rule when it occurs at (begin,split,end)
   */
  def scoreBinaryRule(begin: Int, split: Int, end: Int, rule: Int): Double

  /**
   * Scores the indexed [[epic.trees.UnaryRule]] rule when it occurs at (begin,end)
   */
  def scoreUnaryRule(begin: Int, end: Int, rule: Int): Double

  /**
   * Scores the indexed label rule when it occurs at (begin,end). Can be used for tags, or for a
   * "bottom" label. Typically it is used to filter out impossible rules (using Double.NegativeInfinity)
   */
  def scoreSpan(begin: Int, end: Int, tag: Int): Double

  // Factor stuff
  /**
   * Computes the point-wise product of this grammar with some other grammar.
   *
   * Note that scores are in log space, so we actually sum scores.
   * @param other
   * @return
   */
  def *(other: CoreAnchoring[L, W]) = {
    // hacky multimethod dispatch is hacky
    if (other eq null) this // ugh
    else if(other.isInstanceOf[CoreAnchoring.Identity[L, W]] && this.isInstanceOf[CoreAnchoring.Identity[L, W]])
      CoreAnchoring.identity(topology, lexicon, words, sparsityPattern & other.sparsityPattern)
    else if(other.isInstanceOf[CoreAnchoring.Identity[L, W]]) this.addConstraints(other.sparsityPattern)
    else if(this.isInstanceOf[CoreAnchoring.Identity[L, W]]) other.addConstraints(this.sparsityPattern)
    else new ProductCoreAnchoring(this,other)
  }

  /**
   * Computes the point-wise division of this grammar with some other grammar.
   *
   * Note that scores are in log space, so we actually subtract scores.
   * @param other
   * @return
   */
  def /(other: CoreAnchoring[L, W]) = {
    // hacky multimethod dispatch is hacky
    if (other eq null) this // ugh
    else if(this eq other) new CoreAnchoring.Identity[L, W](topology, lexicon, words, sparsityPattern)
    else if(other.isInstanceOf[CoreAnchoring.Identity[L, W]]) this.addConstraints(other.sparsityPattern)
    else new ProductCoreAnchoring(this, other, -1)
  }

  /**
   * Computes the log-logPartition for this anchoring,
   * which is to say the inside score at the root.
   *
   * @return
   */
  def logPartition = marginal.logPartition

  /** Is this CoreAnchoring nearly the same as that core anchoring? */
  def isConvergedTo(f: CoreAnchoring[L, W], diff: Double) = lift.isConvergedTo(f.lift,diff)

  /** The posterior parse forest for this anchoring */
  def marginal = AugmentedAnchoring.fromCore(this).marginal

  /** The posterior parse forest for this anchoring */
  def maxMarginal = AugmentedAnchoring.fromCore(this).maxMarginal

  def lift:RefinedAnchoring[L, W] = LiftedCoreAnchoring(this)
}

object CoreAnchoring {
  /**
   * Returns an [[epic.parser.CoreAnchoring.Identity]], which assigns 0
   * to everything that is allowed.
   * @param topology
   * @param lexicon
   * @param words
   * @tparam L
   * @tparam W
   * @return
   */
  def identity[L, W](topology: RuleTopology[L],
                     lexicon: Lexicon[L, W],
                     words: IndexedSeq[W],
                     constraints: ChartConstraints[L] = ChartConstraints.noSparsity[L]):CoreAnchoring[L, W] = {
    new Identity(topology, lexicon, words, constraints)
  }

  /**
   * Assigns 0 to everything
   * @param topology
   * @param lexicon
   * @param words
   * @tparam L
   * @tparam W
   * @return
   */
  @SerialVersionUID(1L)
  case class Identity[L, W](topology: RuleTopology[L], lexicon: Lexicon[L, W], words: IndexedSeq[W],
                            override val sparsityPattern: ChartConstraints[L]) extends CoreAnchoring[L, W] {

    def addConstraints(cs: ChartConstraints[L]): CoreAnchoring[L, W] = copy(sparsityPattern = sparsityPattern & cs)

    def scoreBinaryRule(begin: Int, split: Int, end: Int, rule: Int) = 0.0

    def scoreUnaryRule(begin: Int, end: Int, rule: Int) = logI(sparsityPattern.top.isAllowedLabeledSpan(begin, end, topology.parent(rule)))

    def scoreSpan(begin: Int, end: Int, tag: Int) = logI(sparsityPattern.bot.isAllowedLabeledSpan(begin, end, tag))
  }

}

/**
 * Turns a [[epic.parser.CoreAnchoring]] into a [[epic.parser.RefinedAnchoring]]
 * @param core
 * @tparam L
 * @tparam W
 */
@SerialVersionUID(1)
case class LiftedCoreAnchoring[L, W](core: CoreAnchoring[L, W]) extends RefinedAnchoring[L, W] {
  override def annotationTag = 0

  def topology = core.topology

  def lexicon = core.lexicon

  def words = core.words

  final def scoreSpan(begin: Int, end: Int, label: Int, ref: Int) = {
    core.scoreSpan(begin, end, label)
  }

  final def scoreBinaryRule(begin: Int, split: Int, end: Int, rule: Int, ref: Int) = {
    core.scoreBinaryRule(begin, split, end, rule)
  }

  final def scoreUnaryRule(begin: Int, end: Int, rule: Int, ref: Int) = {
    core.scoreUnaryRule(begin, end, rule)
  }

  final def validLabelRefinements(begin: Int, end: Int, label: Int) = Array(0)

  final def numValidRefinements(label: Int) = 1

  final def numValidRuleRefinements(rule: Int) = 1

  private final val zeroArray = Array(0)
  final def validRuleRefinementsGivenParent(begin: Int, end: Int, rule: Int, parentRef: Int) = zeroArray

  final def validUnaryRuleRefinementsGivenChild(begin: Int, end: Int, rule: Int, childRef: Int) = zeroArray

  final def leftChildRefinement(rule: Int, ruleRef: Int) = 0

  final def rightChildRefinement(rule: Int, ruleRef: Int) = 0

  final def parentRefinement(rule: Int, ruleRef: Int) = 0

  final def childRefinement(rule: Int, ruleRef: Int) = 0

  final def ruleRefinementFromRefinements(r: Int, refA: Int, refB: Int) = 0

  final def ruleRefinementFromRefinements(r: Int, refA: Int, refB: Int, refC: Int) = 0

  def validCoarseRulesGivenParentRefinement(a: Int, refA: Int) = topology.indexedBinaryRulesWithParent(a)

  def validParentRefinementsGivenRule(begin: Int, splitBegin: Int, splitEnd: Int, end: Int, rule: Int): Array[Int] = validLabelRefinements(begin, end, topology.parent(rule))

  def validRuleRefinementsGivenLeftChild(begin: Int, split: Int, completionBegin: Int, completionEnd: Int, rule: Int, childRef: Int): Array[Int] = {
    zeroArray
  }

  def validRuleRefinementsGivenRightChild(completionBegin: Int, completionEnd: Int, split: Int, end: Int, rule: Int, childRef: Int): Array[Int] =  {
    zeroArray
  }

  def validLeftChildRefinementsGivenRule(begin: Int, end: Int, completionBegin: Int, completionEnd: Int, rule: Int): Array[Int] =  {
    zeroArray
  }

  def validRightChildRefinementsGivenRule(completionBegin: Int, completionEnd: Int, begin: Int, end: Int, rule: Int): Array[Int] =  {
    zeroArray
  }
}



