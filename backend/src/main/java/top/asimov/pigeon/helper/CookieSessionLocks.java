package top.asimov.pigeon.helper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;
import top.asimov.pigeon.model.enums.CookiePlatform;

/**
 * Serializes access to a stored cookie session.
 *
 * <p>Three concurrent downloads plus the refresh scheduler read and write the same cookie jar. Two
 * separate locks keep that safe without slowing downloads down:
 *
 * <ul>
 *   <li>the content lock is held only while a snapshot is taken or a merge is written back, never
 *       for the duration of a download;
 *   <li>the rotation lock guards a whole refresh round trip so two refreshes cannot race.
 * </ul>
 *
 * <p>The rotation lock is always acquired before the content lock, never the other way round.
 */
@Component
public class CookieSessionLocks {

  private final Map<CookiePlatform, ReentrantReadWriteLock> contentLocks = new ConcurrentHashMap<>();
  private final Map<CookiePlatform, ReentrantLock> rotationLocks = new ConcurrentHashMap<>();

  public ReentrantReadWriteLock contentLock(CookiePlatform platform) {
    return contentLocks.computeIfAbsent(platform, key -> new ReentrantReadWriteLock());
  }

  public ReentrantLock rotationLock(CookiePlatform platform) {
    return rotationLocks.computeIfAbsent(platform, key -> new ReentrantLock());
  }
}
