/*
 * Copyright 2018-2021 OVO Energy Limited
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package fs2.kafka

import cats.{ApplicativeError, Eq, Show}
import cats.instances.string._
import cats.syntax.show._
import fs2.kafka.consumer.KafkaCommit
import fs2.kafka.instances._
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition

/**
  * [[CommittableOffset]] represents an [[offsetAndMetadata]] for a
  * [[topicPartition]], along with the ability to commit that offset
  * to Kafka with [[commit]]. Note that offsets are normally committed
  * in batches for performance reasons. Pipes like [[commitBatchWithin]]
  * use [[CommittableOffsetBatch]] to commit the offsets in batches.<br>
  * <br>
  * While normally not necessary, [[CommittableOffset#apply]] can be
  * used to create a new instance.
  */
sealed abstract class CommittableOffset[F[_]] {

  /**
    * The topic and partition for which [[offsetAndMetadata]]
    * can be committed using [[commit]].
    */
  def topicPartition: TopicPartition

  /**
    * The offset and metadata for the [[topicPartition]], which
    * can be committed using [[commit]].
    */
  def offsetAndMetadata: OffsetAndMetadata

  /**
    * The consumer group ID of the consumer that fetched the
    * [[offsetAndMetadata]] from the [[topicPartition]] from
    * Kafka.<br>
    * <br>
    * Required for committing offsets within a transaction.
    */
  def consumerGroupId: Option[String]

  /**
    * The [[topicPartition]] and [[offsetAndMetadata]] as a `Map`.
    * This is provided for convenience and is always guaranteed to
    * be equivalent to the following.
    *
    * {{{
    * Map(topicPartition -> offsetAndMetadata)
    * }}}
    */
  def offsets: Map[TopicPartition, OffsetAndMetadata]

  /**
    * The [[CommittableOffset]] as a [[CommittableOffsetBatch]].
    */
  def batch: CommittableOffsetBatch[F]

  def committer: KafkaCommit[F]
}

object CommittableOffset {

  /**
    * Creates a new [[CommittableOffset]] with the specified `topicPartition`
    * and `offsetAndMetadata`, along with `commit`, describing how to commit
    * an arbitrary `Map` of topic-partition offsets.
    */
  def apply[F[_]](
    topicPartition: TopicPartition,
    offsetAndMetadata: OffsetAndMetadata,
    consumerGroupId: Option[String],
    committer: KafkaCommit[F]
  )(implicit F: ApplicativeError[F, Throwable]): CommittableOffset[F] = {
    val _topicPartition = topicPartition
    val _offsetAndMetadata = offsetAndMetadata
    val _consumerGroupId = consumerGroupId
    val _committer = committer

    new CommittableOffset[F] {
      override val topicPartition: TopicPartition =
        _topicPartition

      override val offsetAndMetadata: OffsetAndMetadata =
        _offsetAndMetadata

      override val consumerGroupId: Option[String] =
        _consumerGroupId

      override def offsets: Map[TopicPartition, OffsetAndMetadata] =
        Map(_topicPartition -> _offsetAndMetadata)

      override def committer: KafkaCommit[F] =
        _committer

      override def batch: CommittableOffsetBatch[F] =
        CommittableOffsetBatch(offsets, consumerGroupId.toSet, consumerGroupId.isEmpty, committer)

      override def toString: String =
        consumerGroupId match {
          case Some(consumerGroupId) =>
            show"CommittableOffset($topicPartition -> $offsetAndMetadata, $consumerGroupId)"
          case None =>
            show"CommittableOffset($topicPartition -> $offsetAndMetadata)"
        }
    }
  }

  implicit class CommittableOffsetOps[F[_]](val self: CommittableOffset[F]) extends AnyVal {
    /**
      * Commits the [[offsetAndMetadata]] for the [[topicPartition]] to
      * Kafka. Note that offsets are normally committed in batches for
      * performance reasons. Prefer pipes like [[commitBatchWithin]]
      * or [[CommittableOffsetBatch]] for that reason.
      */
    def commit: F[Unit] =
      self.committer.commitInternal(self.offsets)

    /**
      * The commit function we are using in [[commit]] to commit the
      * [[offsetAndMetadata]] for the [[topicPartition]]. Is used to
      * help achieve better performance when batching offsets.
      */
    private[kafka] def commitOffsets: Map[TopicPartition, OffsetAndMetadata] => F[Unit] =
      self.committer.commitInternal
  }

  implicit def committableOffsetShow[F[_]]: Show[CommittableOffset[F]] =
    Show.fromToString

  implicit def committableOffsetEq[F[_]]: Eq[CommittableOffset[F]] =
    Eq.instance {
      case (l, r) =>
        l.topicPartition == r.topicPartition &&
          l.offsetAndMetadata == r.offsetAndMetadata &&
          l.consumerGroupId == r.consumerGroupId &&
          l.committer.eq(r.committer)
    }
}
