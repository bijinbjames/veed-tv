package home.veed.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

class VeedTvApp : Application(), ImageLoaderFactory {
    /**
     * Channel logos. Wikimedia (a fifth of the logos) answers 403 to OkHttp's anonymous default
     * User-Agent and 200 once the app names itself. Software bitmaps, because ChannelLogo reads
     * the pixels to choose a light or dark tile.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", LOGO_USER_AGENT).build())
                }
                .build()
        }
        .allowHardware(false)
        .crossfade(false)
        .build()

    companion object {
        // The same name the server checks logos with, so a logo that passed there loads here.
        const val LOGO_USER_AGENT = "VeedTV (home TV app; channel logos from iptv-org)"
    }
}
