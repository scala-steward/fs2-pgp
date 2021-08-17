package com.dwolla.security

import eu.timepit.refined.api.RefType
import eu.timepit.refined.types.all._
import eu.timepit.refined.auto._
import eu.timepit.refined.predicates.all.Positive
import eu.timepit.refined.refineV
import shapeless.tag
import shapeless.tag.@@
import org.bouncycastle.openpgp.PGPLiteralData

package object crypto {
  type ChunkSize = PosInt @@ ChunkSizeTag

  def tagChunkSize(pi: PosInt): ChunkSize = tag[ChunkSizeTag][PosInt](pi)
  def attemptTagChunkSize(pi: Int): Either[String, ChunkSize] = refineV[Positive](pi).map(tagChunkSize)

  val defaultChunkSize: ChunkSize = tagChunkSize(4096)

  implicit def taggedAutoUnwrap[R[_, _], T, P, Tag](tp: R[T, P] @@ Tag)(implicit rt: RefType[R]): T =
    rt.unwrap(tp)
}

package crypto {
  trait ChunkSizeTag

  sealed trait Encryption {
    val tag: Int
  }

  object Encryption {
    //    case object Idea extends Encryption { override val tag: Int = 1 }
    //    case object TripleDes extends Encryption { override val tag: Int = 2 }
    //    case object Cast5 extends Encryption { override val tag: Int = 3 }
    //    case object Blowfish extends Encryption { override val tag: Int = 4 }
    //    case object Safer extends Encryption { override val tag: Int = 5 }
    //    case object Des extends Encryption { override val tag: Int = 6 }
    //    case object Aes128 extends Encryption { override val tag: Int = 7 }
    //    case object Aes192 extends Encryption { override val tag: Int = 8 }
    case object Aes256 extends Encryption { override val tag: Int = 9 }
    //    case object TwoFish extends Encryption { override val tag: Int = 10 }
    //    case object Camellia128 extends Encryption { override val tag: Int = 11 }
    //    case object Camellia192 extends Encryption { override val tag: Int = 12 }
    //    case object Camellia256 extends Encryption { override val tag: Int = 13 }
  }

  sealed trait Compression {
    val tag: Int
  }

  object Compression {
    case object Uncompressed extends Compression { override val tag: Int = 0 }
    case object Zip extends Compression { override val tag: Int = 1 }
    case object Zlib extends Compression { override val tag: Int = 2 }
    case object Bzip2 extends Compression { override val tag: Int = 3 }
  }

  sealed trait PgpLiteralDataPacketFormat {
    val tag: Char
  }

  object PgpLiteralDataPacketFormat {
    case object Binary extends PgpLiteralDataPacketFormat { override val tag: Char = PGPLiteralData.BINARY }
    case object Text extends PgpLiteralDataPacketFormat { override val tag: Char = PGPLiteralData.TEXT }
    case object Utf8 extends PgpLiteralDataPacketFormat { override val tag: Char = PGPLiteralData.UTF8 }
  }
}
