#pragma once
#include <jni.h>
#include "hires_core.h"

namespace Audiophile::Jni {

/**
 * Turns a Kotlin com.audiophile.engine.library.source.RemoteByteSource into the C API's
 * hrc_byte_source_callbacks (size, read_at, release), so Drive and Telegram files plug into
 * hrc_open / hrc_queue_next as HRC_SOURCE_CALLBACKS.
 *
 * Reads run on the decoder thread, which is a native thread: it is attached to the JVM on its
 * first read and detached when the thread exits. Kotlin receives a direct ByteBuffer that wraps
 * the engine's destination memory, so bytes are written in place with no copy. A Kotlin
 * exception is cleared, logged and reported as -1 (HRC_E_IO upstream).
 *
 * The user pointer returned by Create is owned by the engine; the engine hands it back to
 * KotlinByteSourceCallbacks().release, which closes the Kotlin object and frees everything.
 */
void* CreateKotlinByteSource(JNIEnv* env, jobject remoteByteSource) noexcept;
const hrc_byte_source_callbacks& KotlinByteSourceCallbacks() noexcept;

} // namespace Audiophile::Jni
