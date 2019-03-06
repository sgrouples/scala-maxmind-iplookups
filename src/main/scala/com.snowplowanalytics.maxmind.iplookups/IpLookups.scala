/*
 * Copyright (c) 2012-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.maxmind.iplookups

import java.io.File
import java.net.InetAddress

import cats.{Eval, Monad}
import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import com.maxmind.db.CHMCache
import com.maxmind.geoip2.DatabaseReader
import com.snowplowanalytics.lrumap.{CreateLruMap, LruMap}

import model._

/** Companion object to hold alternative constructors. */
object IpLookups {

  /**
   * Create an IpLookups from Files
   * @param geoFile Geographic lookup database file
   * @param ispFile ISP lookup database file
   * @param domainFile Domain lookup database file
   * @param connectionTypeFile Connection type lookup database file
   * @param memCache Whether to use MaxMind's CHMCache
   * @param lruCacheSize Maximum size of LruMap cache
   */
  def createFromFiles[F[_]: Sync](
    geoFile: Option[File] = None,
    ispFile: Option[File] = None,
    domainFile: Option[File] = None,
    connectionTypeFile: Option[File] = None,
    memCache: Boolean = true,
    lruCacheSize: Int = 10000
  )(implicit CLM: CreateLruMap[F, String, IpLookupResult]): F[IpLookups[F]] =
    (
      if (lruCacheSize > 0) {
        CLM.create(lruCacheSize).map(_.some)
      } else {
        Sync[F].pure(None)
      }
    ).flatMap { lruCache =>
      Sync[F].delay {
        new IpLookups(
          geoFile,
          ispFile,
          domainFile,
          connectionTypeFile,
          memCache,
          lruCache
        )
      }
    }

  /**
   * Create an unsafe IpLookups from Files
   * @param geoFile Geographic lookup database file
   * @param ispFile ISP lookup database file
   * @param domainFile Domain lookup database file
   * @param connectionTypeFile Connection type lookup database file
   * @param memCache Whether to use MaxMind's CHMCache
   * @param lruCacheSize Maximum size of LruMap cache
   */
  def unsafeCreateFromFiles(
    geoFile: Option[File] = None,
    ispFile: Option[File] = None,
    domainFile: Option[File] = None,
    connectionTypeFile: Option[File] = None,
    memCache: Boolean = true,
    lruCacheSize: Int = 10000
  )(implicit CLM: CreateLruMap[Eval, String, IpLookupResult]): Eval[IpLookups[Eval]] =
    (
      if (lruCacheSize > 0) {
        CLM.create(lruCacheSize).map(_.some)
      } else {
        Eval.now(None)
      }
    ).flatMap { lruCache =>
      Eval.later {
        new IpLookups(
          geoFile,
          ispFile,
          domainFile,
          connectionTypeFile,
          memCache,
          lruCache
        )
      }
    }

  /**
   * Alternative constructor taking filenames rather than Files
   * @param geoFile Geographic lookup database filepath
   * @param ispFile ISP lookup database filepath
   * @param domainFile Domain lookup database filepath
   * @param connectionTypeFile Connection type lookup database filepath
   * @param memCache Whether to use MaxMind's CHMCache
   * @param lruCacheSize Maximum size of LruMap cache
   */
  def createFromFilenames[F[_]: Sync](
    geoFile: Option[String] = None,
    ispFile: Option[String] = None,
    domainFile: Option[String] = None,
    connectionTypeFile: Option[String] = None,
    memCache: Boolean = true,
    lruCacheSize: Int = 10000
  ): F[IpLookups[F]] =
    IpLookups.createFromFiles(
      geoFile.map(new File(_)),
      ispFile.map(new File(_)),
      domainFile.map(new File(_)),
      connectionTypeFile.map(new File(_)),
      memCache,
      lruCacheSize
    )

  /**
   * Alternative unsafe constructor taking filenames rather than Files
   * @param geoFile Geographic lookup database filepath
   * @param ispFile ISP lookup database filepath
   * @param domainFile Domain lookup database filepath
   * @param connectionTypeFile Connection type lookup database filepath
   * @param memCache Whether to use MaxMind's CHMCache
   * @param lruCacheSize Maximum size of LruMap cache
   */
  def unsafeCreateFromFilenames(
    geoFile: Option[String] = None,
    ispFile: Option[String] = None,
    domainFile: Option[String] = None,
    connectionTypeFile: Option[String] = None,
    memCache: Boolean = true,
    lruCacheSize: Int = 10000
  ): Eval[IpLookups[Eval]] =
    IpLookups.unsafeCreateFromFiles(
      geoFile.map(new File(_)),
      ispFile.map(new File(_)),
      domainFile.map(new File(_)),
      connectionTypeFile.map(new File(_)),
      memCache,
      lruCacheSize
    )
}

/**
 * IpLookups is a Scala wrapper around MaxMind's own DatabaseReader Java class.
 * Two main differences:
 * 1. getLocation(ipS: String) now returns an IpLocation
 *    case class, not a raw MaxMind Location
 * 2. IpLookups introduces an LRU cache to improve
 *    lookup performance
 * Inspired by:
 * https://github.com/jt6211/hadoop-dns-mining/blob/master/src/main/java/io/covert/dns/geo/IpLookups.java
 */
class IpLookups[F[_]: Monad] private (
  geoFile: Option[File],
  ispFile: Option[File],
  domainFile: Option[File],
  connectionTypeFile: Option[File],
  memCache: Boolean,
  lru: Option[LruMap[F, String, IpLookupResult]]
)(
  implicit
  SR: SpecializedReader[F],
  IAR: IpAddressResolver[F]) {
  // Configure the lookup services
  private val geoService    = getService(geoFile)
  private val ispService    = getService(ispFile).map((_, ReaderFunctions.isp))
  private val orgService    = getService(ispFile).map((_, ReaderFunctions.org))
  private val domainService = getService(domainFile).map((_, ReaderFunctions.domain))
  private val connectionTypeService =
    getService(connectionTypeFile).map((_, ReaderFunctions.connectionType))

  /**
   * Get a LookupService from a database file
   *
   * @param serviceFile The database file
   * @return LookupService
   */
  private def getService(serviceFile: Option[File]): Option[DatabaseReader] =
    serviceFile.map { f =>
      val builder = new DatabaseReader.Builder(f)
      (
        if (memCache) builder.withCache(new CHMCache())
        else builder
      ).build()
    }

  /**
   * Creates an Either from an IPLookup
   * @param service ISP, domain or connection type LookupService
   * @return the result of the lookup
   */
  private def getLookup(
    ipAddress: Either[Throwable, InetAddress],
    service: Option[(DatabaseReader, ReaderFunction)]
  ): F[Option[Either[Throwable, String]]] =
    (ipAddress, service) match {
      case (Right(ipA), Some((db, f))) =>
        SR.getValue(f, db, ipA).map(_.some)
      case (Left(f), _) =>
        Monad[F].pure(Some(Left(f)))
      case _ =>
        Monad[F].pure(None)
    }

  /**
   * Returns the MaxMind location for this IP address
   * as an IpLocation, or None if MaxMind cannot find
   * the location.
   */
  def performLookups(s: String): F[IpLookupResult] =
    lru
      .map(performLookupsWithLruCache(_, s))
      .getOrElse(performLookupsWithoutLruCache(s))

  private def getLocationLookup(
    ipAddress: Either[Throwable, InetAddress]
  ): F[Option[Either[Throwable, IpLocation]]] = (ipAddress, geoService) match {
    case (Right(ipA), Some(gs)) =>
      SR.getCityValue(gs, ipA)
        .map(loc => loc.map(IpLocation(_)).some)
    case (Left(f), _) => Monad[F].pure(Some(Left(f)))
    case _            => Monad[F].pure(None)
  }

  /**
   * This version does not use the LRU cache.
   * Concurrently looks up information
   * based on an IP address from one or
   * more MaxMind LookupServices
   *
   * @param ip IP address
   * @return Tuple containing the results of the
   *         LookupServices
   */
  private def performLookupsWithoutLruCache(ip: String): F[IpLookupResult] =
    for {
      ipAddress <- IAR.resolve(ip)

      ipLocation     <- getLocationLookup(ipAddress)
      isp            <- getLookup(ipAddress, ispService)
      org            <- getLookup(ipAddress, orgService)
      domain         <- getLookup(ipAddress, domainService)
      connectionType <- getLookup(ipAddress, connectionTypeService)
    } yield IpLookupResult(ipLocation, isp, org, domain, connectionType)

  /**
   * Returns the MaxMind location for this IP address
   * as an IpLocation, or None if MaxMind cannot find
   * the location.
   *
   * This version uses and maintains the LRU cache.
   *
   * Don't confuse the LRU returning None (meaning that no
   * cache entry could be found), versus an extant cache entry
   * containing None (meaning that the IP address is unknown).
   */
  private def performLookupsWithLruCache(
    lru: LruMap[F, String, IpLookupResult],
    ip: String
  ): F[IpLookupResult] = {
    val lookupAndCache =
      performLookupsWithoutLruCache(ip).flatMap(result => {
        lru.put(ip, result).map(_ => result)
      })

    lru
      .get(ip)
      .map(_.map(Monad[F].pure(_)))
      .flatMap(_.getOrElse(lookupAndCache))
  }
}
