package com.dwolla.security.crypto

import cats.effect._
import cats.syntax.all._
import com.dwolla.testutils._
import fs2._
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.bouncycastle.bcpg._
import org.bouncycastle.openpgp._
import org.scalacheck.Arbitrary._
import org.scalacheck._
import org.scalacheck.effect.PropF.{forAllF, forAllNoShrinkF}
import org.scalacheck.util.Pretty
import org.typelevel.log4cats._
import org.typelevel.log4cats.noop.NoOpLogger
import com.eed3si9n.expecty.Expecty.{ assert => Assert }

import java.io.ByteArrayOutputStream
import scala.concurrent.duration._

class CryptoAlgSpec
  extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with PgpArbitraries
    with CryptoArbitraries {

  private implicit val noOpLogger: LoggerFactory[IO] = new LoggerFactory[IO] {
    override def getLoggerFromName(name: String): SelfAwareStructuredLogger[IO] = NoOpLogger[IO]
    override def fromName(name: String): IO[SelfAwareStructuredLogger[IO]] = NoOpLogger[IO].pure[IO]
  }

  private val resource: Fixture[CryptoAlg[IO]] = ResourceSuiteLocalFixture("CryptoAlg[IO]", CryptoAlg[IO])
  override def munitFixtures = List(resource)

  override protected def scalaCheckTestParameters: Test.Parameters =
    Test.Parameters.default
      .withMinSuccessfulTests(1)

  override val munitTimeout: Duration = 2.minutes

  test("CryptoAlg should round trip the plaintext using a key pair") {
    val crypto = resource()
    implicit val arbKeyPair: Arbitrary[Resource[IO, PGPKeyPair]] = arbWeakKeyPair[IO]

    forAllF { (keyPairR: Resource[IO, PGPKeyPair],
               bytesG: Stream[Pure, Byte],
               encryptionChunkSize: ChunkSize,
               decryptionChunkSize: ChunkSize) =>
      val materializedBytes: List[Byte] = bytesG.compile.toList
      val bytes = Stream.emits(materializedBytes)
      val testResource =
        for {
          keyPair <- keyPairR
          roundTrip <- bytes
            .through(crypto.encrypt(keyPair.getPublicKey, encryptionChunkSize))
            .through(crypto.armor(encryptionChunkSize))
            .through(crypto.decrypt(keyPair.getPrivateKey, decryptionChunkSize))
            .compile
            .resource
            .toList
        } yield roundTrip

      testResource.use { roundTrip => IO {
        assertEquals(roundTrip, materializedBytes)
      }}
    }
  }

  test("CryptoAlg should maintain chunk size throughout pipeline") {
    val crypto = resource()

    case class Inputs(keyPairR: Resource[IO, PGPKeyPair],
                      encryptionChunkSize: ChunkSize,
                      bytes: Stream[Pure, Byte],
                     )
    val genChunkSizeTestInputs: Gen[Inputs] =
      for {
        keyPairR <- genWeakKeyPair[IO]
        encryptionChunkSize <- arbitrary[ChunkSize]
        // since the cryptotext is compressed, we need to generate at least 10x the chunk size to
        // be fairly confident that there will be at least one full-sized chunk
        bytes <- genNBytesBetween(encryptionChunkSize.value * 10, 1 << 16)
      } yield Inputs(keyPairR, encryptionChunkSize, bytes)

    forAllNoShrinkF(genChunkSizeTestInputs) { case Inputs(keyPairR, encryptionChunkSize, bytes) =>
      val testResource =
        for {
          keyPair <- keyPairR
          chunkSizes <- bytes
            .through(crypto.encrypt(keyPair.getPublicKey, encryptionChunkSize))
            .through(crypto.armor(encryptionChunkSize))
            .chunks
            .map(_.size)
            .compile
            .resource
            .to(Set)
        } yield chunkSizes

      testResource.use(chunkSizes => IO {
        Assert(chunkSizes.contains(encryptionChunkSize.value))
        Assert(Set(1, 2) contains chunkSizes.size)
      })
    }
  }

  test("CryptoAlg should support armoring a PGP value") {
    val crypto = resource()
    implicit val arbKeyPair: Arbitrary[Resource[IO, PGPKeyPair]] = arbWeakKeyPair[IO]

    forAllF(genPgpBytes[IO]) { (bytesR: Resource[IO, Array[Byte]]) =>
      val testResource =
        for {
          bytes <- bytesR
          armored <- Stream.emits(bytes).through(crypto.armor()).through(text.utf8.decode).compile.resource.string
          expected <- Resource.eval {
            for {
              out <- IO(new ByteArrayOutputStream())
              _ <- Resource.fromAutoCloseable(IO(new ArmoredOutputStream(out))).evalMap(arm => IO(arm.write(bytes))).use(_ => IO.unit)
              s <- IO(new String(out.toByteArray))
            } yield s
          }
        } yield (armored, expected)

      testResource.use { case (armored, expected) => IO {
        assertEquals(armored, expected)
      }}
    }
  }

  test("CryptoAlg should round trip the plaintext using a key ring collection") {
    val crypto = resource()

    case class Inputs(passphrase: Array[Char],
                      bytesG: Stream[Pure, Byte],
                      encryptionChunkSize: ChunkSize,
                      decryptionChunkSize: ChunkSize,
                      collectionR: Resource[IO, PGPSecretKeyRingCollection]
                     )
    implicit val arbKeyPair: Arbitrary[Resource[IO, PGPKeyPair]] = arbWeakKeyPair[IO]
    val genInputs: Gen[Inputs] =
      for {
        passphrase <- arbitrary[Array[Char]]
        bytesG <- arbitrary[Stream[Pure, Byte]]
        encryptionChunkSize <- arbitrary[ChunkSize]
        decryptionChunkSize <- arbitrary[ChunkSize]
        collectionR <- genPGPSecretKeyRingCollection[IO](passphrase)
      } yield Inputs(passphrase, bytesG, encryptionChunkSize, decryptionChunkSize, collectionR)

    forAllNoShrinkF(genInputs) { case Inputs(passphrase, bytesG, encryptionChunkSize, decryptionChunkSize, collectionR) =>
      val materializedBytes: List[Byte] = bytesG.compile.toList

      val testResource =
        for {
          collection <- collectionR
          pub <- keysIn[IO](collection).map(_.getPublicKey).find(_.isEncryptionKey).compile.resource.lastOrError
          roundTrip <- Stream.emits(materializedBytes)
            .through(crypto.encrypt(pub, encryptionChunkSize))
            .through(crypto.armor(encryptionChunkSize))
            .through(crypto.decrypt(collection, passphrase, decryptionChunkSize))
            .compile
            .resource
            .toList
        } yield roundTrip

      testResource.use(roundTrip => IO {
        assertEquals(roundTrip, materializedBytes)
      })
    }
  }

  test("CryptoAlg should round trip the plaintext using a key ring") {
    val crypto = resource()
    implicit val arbKeyPair: Arbitrary[Resource[IO, PGPKeyPair]] = arbWeakKeyPair[IO]

    forAllNoShrinkF { (keyPairR: Resource[IO, PGPKeyPair],
                       bytesG: Stream[Pure, Byte],
                       encryptionChunkSize: ChunkSize,
                       decryptionChunkSize: ChunkSize,
                       keyRingId: String,
                       passphrase: Array[Char]) =>
      val materializedBytes: List[Byte] = bytesG.compile.toList

      val testResource =
        for {
          kp <- keyPairR
          ring <- Resource.eval(pgpKeyRingGenerator[IO](keyRingId, kp, passphrase)).map(_.generateSecretKeyRing())
          roundTrip <- Stream.emits(materializedBytes)
            .through(crypto.encrypt(kp.getPublicKey, encryptionChunkSize))
            .through(crypto.armor(encryptionChunkSize))
            .through(crypto.decrypt(ring, passphrase, decryptionChunkSize))
            .compile
            .resource
            .toList
        } yield roundTrip

      testResource.use(roundTrip => IO {
        assertEquals(roundTrip, materializedBytes)
      })
    }
  }

  /*
   * Steps to generate cryptotext on macOS, having copied the test key to the clipboard:
   *
   *  > export GNUPGHOME="$(mktemp -d)/.gnupg"
   *  > mkdir -m 0700 -p "${GNUPGHOME}"
   *  > pbpaste | gpg --import
   *  > echo "Hello World" | gpg --encrypt --armor --hidden-recipient "key 1 <key1@dwolla.com>"
   */
  test("CryptoAlg should decrypt cryptotext with a hidden recipient using secret key collection") {
    val crypto = resource()

    val message =
      """-----BEGIN PGP MESSAGE-----
        |
        |hQGMAwAAAAAAAAAAAQv/QQ8RnRGF6jaPTUpuBoPollIBvPIzqokTGzuTaVD4bKsg
        |GGt4ooPpcTkxn0MRLs3rNJfZjaULSkWtUxc4NsSbqmrl8g3smnwJWk/UIR097zlC
        |s30/o3WlmSodAGbEuP5Y+mbAErwGbCs1e7cn1LqQO3BrSZ3m7djif9fiWRdb3AZ4
        |YPX5dmmOZLZoNQO5zLNu3iolrTXyimQLcS7VoFQ+Nbj9hOS+vDzcg6Kycaky7U+M
        |arfyyaqWan8hVygDthMT+n3Au0l7lBzN99aZmC13OP2fhuBBXvrGF+njFS+RkEOs
        |LToMlpFVYWlEFSnYlIQjsxKBzMKThNudKM7r4Kc1yw88DQ9C/rWZxmMxTyLAA4C7
        |QZKdO+zYfzSCYq3bO+YdN8vUGZPS63YN8Pp6qWvIXOZ3oecxmidqjGsItLpxJ0KK
        |zJ0IWVsQj1Zc/2zSojw8edcMh86PFbQsC4aPpMK54KiU4YKXcUnaDeQ48BGv27Po
        |Qx9PqZi+1ROo+anCVWrn0kcB/+g0TzSpG+nwMI4gxNTTAuybzEscK2ifkA76Df45
        |cSypFoj4OIRtTZ8iSGhfgt0fCn1qUrEs7Vw+iNYSqpl9/ue3u1icCQ==
        |=kHjl
        |-----END PGP MESSAGE-----
        |""".stripMargin

    for {
      key <- PGPKeyAlg[IO].readSecretKeyCollection(TestKey())
      text <- Stream
        .emit(message)
        .through(text.utf8.encode)
        .through(crypto.decrypt(key))
        .through(text.utf8.decode)
        .compile
        .lastOrError
    } yield {
      assertEquals(text, "Hello World\n")
    }
  }

  /*
   * Steps to generate cryptotext on macOS, having copied the test key to the clipboard:
   *
   *  > export GNUPGHOME="$(mktemp -d)/.gnupg"
   *  > mkdir -m 0700 -p "${GNUPGHOME}"
   *  > pbpaste | gpg --import
   *  > echo "Hello World" | gpg --encrypt --armor --recipient "key 1 <key1@dwolla.com>"
   */
  test("CryptoAlg should decrypt cryptotext with a known recipient using secret key collection") {
    val crypto = resource()

    val message =
      """-----BEGIN PGP MESSAGE-----
        |
        |hQGMA1TkhEz+fGjhAQwAmJa5ZefcoKp0xp2AyPf1W/cPb/L7qwohnxiwjOmOZOWx
        |APAbFWbqqMCUqoFqWIE3Uo1k2xfBe+gy3lZzEpaNcWqo5cNcFRajZCpC4Jh5AWKZ
        |z3wTzlmKoO+JRi7PshuPbGeiYNRYiayfc2L9bQFB2zFx/99Q542oQmRo/dFtjpNQ
        |rROUmGmhuZTFKFoCa8EQlglOu0tUH4pn79mA3POwiYKSO+nySOXlciOUzofauVKz
        |mv0YzEapgmGUSMH7itNa3OWpYuip0EVeg4juoY1Qm0ae+AHV1mGcTn/k5vT4r55Z
        |0yyzRhACb5lnS2OllNVcjV9LkQ8PdosEKGfLDHniF/OaAj7KF79N9CqwMAA1ng1J
        |3oHj3oJcxPEtrccgkGErtQJvrC0UENQGDZS171DXmYKSyiP0W+GU26oeDo5L7vug
        |61LcAL4fKkj6PWkLu/iFoWStnPdI2prqJ4fUGGmYyiTf8sL/HD1Zmj+wgbd4svUo
        |Jlmzgr/sl2FC0avgYnFi0kcBGfqqvZ8D2QSIk+1xmOckKc3MBRYboDYHlaKYecit
        |cWo+Lvh6NbHbJcfDjewdJe2A7FKm6YGZUVhOvZEC1Lf2fQ/A++ZGZg==
        |=TVjb
        |-----END PGP MESSAGE-----
        |""".stripMargin

    for {
      key <- PGPKeyAlg[IO].readSecretKeyCollection(TestKey())
      text <- Stream
        .emit(message)
        .through(text.utf8.encode)
        .through(crypto.decrypt(key))
        .through(text.utf8.decode)
        .compile
        .lastOrError
    } yield {
      assertEquals(text, "Hello World\n")
    }
  }

  /*
   * Steps to generate cryptotext on macOS, having copied the test key to the clipboard:
   *
   *  > export GNUPGHOME="$(mktemp -d)/.gnupg"
   *  > mkdir -m 0700 -p "${GNUPGHOME}"
   *  > pbpaste | gpg --import
   *  > echo "Hello World" | gpg --encrypt --armor --hidden-recipient "key 1 <key1@dwolla.com>"
   */
  test("CryptoAlg should decrypt cryptotext with a hidden recipient using private key") {
    val crypto = resource()

    val message =
      """-----BEGIN PGP MESSAGE-----
        |
        |hQGMAwAAAAAAAAAAAQv/QQ8RnRGF6jaPTUpuBoPollIBvPIzqokTGzuTaVD4bKsg
        |GGt4ooPpcTkxn0MRLs3rNJfZjaULSkWtUxc4NsSbqmrl8g3smnwJWk/UIR097zlC
        |s30/o3WlmSodAGbEuP5Y+mbAErwGbCs1e7cn1LqQO3BrSZ3m7djif9fiWRdb3AZ4
        |YPX5dmmOZLZoNQO5zLNu3iolrTXyimQLcS7VoFQ+Nbj9hOS+vDzcg6Kycaky7U+M
        |arfyyaqWan8hVygDthMT+n3Au0l7lBzN99aZmC13OP2fhuBBXvrGF+njFS+RkEOs
        |LToMlpFVYWlEFSnYlIQjsxKBzMKThNudKM7r4Kc1yw88DQ9C/rWZxmMxTyLAA4C7
        |QZKdO+zYfzSCYq3bO+YdN8vUGZPS63YN8Pp6qWvIXOZ3oecxmidqjGsItLpxJ0KK
        |zJ0IWVsQj1Zc/2zSojw8edcMh86PFbQsC4aPpMK54KiU4YKXcUnaDeQ48BGv27Po
        |Qx9PqZi+1ROo+anCVWrn0kcB/+g0TzSpG+nwMI4gxNTTAuybzEscK2ifkA76Df45
        |cSypFoj4OIRtTZ8iSGhfgt0fCn1qUrEs7Vw+iNYSqpl9/ue3u1icCQ==
        |=kHjl
        |-----END PGP MESSAGE-----
        |""".stripMargin

    for {
      key <- PGPKeyAlg[IO].readPrivateKey(TestKey.privateSubKey)
      text <- Stream
        .emit(message)
        .through(text.utf8.encode)
        .through(crypto.decrypt(key))
        .through(text.utf8.decode)
        .compile
        .lastOrError
    } yield {
      assertEquals(text, "Hello World\n")
    }
  }

  /*
   * Steps to generate cryptotext on macOS, having copied the test key to the clipboard:
   *
   *  > export GNUPGHOME="$(mktemp -d)/.gnupg"
   *  > mkdir -m 0700 -p "${GNUPGHOME}"
   *  > pbpaste | gpg --import
   *  > echo "Hello World" | gpg --encrypt --armor --recipient "key 1 <key1@dwolla.com>"
   */
  test("CryptoAlg should decrypt cryptotext with a known recipient using private key") {
    val crypto = resource()

    val message =
      """-----BEGIN PGP MESSAGE-----
        |
        |hQGMA1TkhEz+fGjhAQwAmJa5ZefcoKp0xp2AyPf1W/cPb/L7qwohnxiwjOmOZOWx
        |APAbFWbqqMCUqoFqWIE3Uo1k2xfBe+gy3lZzEpaNcWqo5cNcFRajZCpC4Jh5AWKZ
        |z3wTzlmKoO+JRi7PshuPbGeiYNRYiayfc2L9bQFB2zFx/99Q542oQmRo/dFtjpNQ
        |rROUmGmhuZTFKFoCa8EQlglOu0tUH4pn79mA3POwiYKSO+nySOXlciOUzofauVKz
        |mv0YzEapgmGUSMH7itNa3OWpYuip0EVeg4juoY1Qm0ae+AHV1mGcTn/k5vT4r55Z
        |0yyzRhACb5lnS2OllNVcjV9LkQ8PdosEKGfLDHniF/OaAj7KF79N9CqwMAA1ng1J
        |3oHj3oJcxPEtrccgkGErtQJvrC0UENQGDZS171DXmYKSyiP0W+GU26oeDo5L7vug
        |61LcAL4fKkj6PWkLu/iFoWStnPdI2prqJ4fUGGmYyiTf8sL/HD1Zmj+wgbd4svUo
        |Jlmzgr/sl2FC0avgYnFi0kcBGfqqvZ8D2QSIk+1xmOckKc3MBRYboDYHlaKYecit
        |cWo+Lvh6NbHbJcfDjewdJe2A7FKm6YGZUVhOvZEC1Lf2fQ/A++ZGZg==
        |=TVjb
        |-----END PGP MESSAGE-----
        |""".stripMargin

    for {
      key <- PGPKeyAlg[IO].readPrivateKey(TestKey.privateSubKey)
      text <- Stream
        .emit(message)
        .through(text.utf8.encode)
        .through(crypto.decrypt(key))
        .through(text.utf8.decode)
        .compile
        .lastOrError
    } yield {
      assertEquals(text, "Hello World\n")
    }
  }

  private implicit def prettyArrayChar: Array[Char] => Pretty = arr => Pretty { _ =>
    arr.toList.map("'" + _ + "'").mkString("Array(", ", ", ")")
  }
}
