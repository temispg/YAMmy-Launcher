package eu.ottop.yamlauncher.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import eu.ottop.yamlauncher.R
import eu.ottop.yamlauncher.settings.SharedPreferenceManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Weather system integration using Open-Meteo API.
 * Handles location acquisition and weather data fetching.
 *
 * Features:
 * - GPS-based location detection
 * - Manual location search via geocoding
 * - Temperature unit conversion (Celsius/Fahrenheit)
 * - Weather condition icons
 */
class WeatherSystem(private val context: Context) {

    private val sharedPreferenceManager = SharedPreferenceManager(context)
    private val stringUtils = StringUtils()
    private val logger = Logger.getInstance(context)

    /**
     * Refreshes the saved weather location from the device's current position.
     * Keeps the previous location if permission is missing or no fix is available.
     * Requires ACCESS_COARSE_LOCATION permission.
     *
     * @suspend Must be called from coroutine context
     */
    suspend fun updateGpsLocation() {

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Check location permission before accessing GPS
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            // Prefer network provider (faster, works indoors), fallback to GPS
            val provider = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }

            // Request current location asynchronously with callback
            val location: Location? = withContext(Dispatchers.IO) {
                try {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val cancellationSignal = CancellationSignal()
                        continuation.invokeOnCancellation { cancellationSignal.cancel() }
                        LocationManagerCompat.getCurrentLocation(
                            locationManager,
                            provider,
                            cancellationSignal,
                            ContextCompat.getMainExecutor(context)
                        ) { loc: Location? ->
                            if (continuation.isActive) continuation.resume(loc, null)
                        }
                    }
                } catch (e: Exception) {
                    logger.w("WeatherSystem", "Failed to get GPS location: ${e.message}")
                    null
                }
            }

            if (location != null) {
                val newLocation = "latitude=${location.latitude}&longitude=${location.longitude}"
                if (newLocation != sharedPreferenceManager.getWeatherLocation()) {
                    sharedPreferenceManager.setWeatherLocation(
                        newLocation,
                        context.getString(R.string.latest_location)
                    )
                }
            }
        } catch (e: Exception) {
            logger.w("WeatherSystem", "Failed to update GPS location: ${e.message}")
        }
    }

    /**
     * Searches for locations matching a search term.
     * Uses Open-Meteo geocoding API.
     *
     * @param searchTerm City name to search for
     * @return List of location maps with name, latitude, longitude, country, region
     * @must Be called from Dispatchers.IO
     */
    fun getSearchedLocations(searchTerm: String?) : MutableList<Map<String, String>> {
        val foundLocations = mutableListOf<Map<String, String>>()

        val trimmedSearchTerm = searchTerm?.trim().orEmpty()
        // Minimum 2 characters to avoid excessive API calls
        if (trimmedSearchTerm.length < 2) return foundLocations

        // URL encode search term for safe API request
        val encodedSearchTerm = try {
            URLEncoder.encode(trimmedSearchTerm, "UTF-8")
        } catch (e: Exception) {
            logger.e("WeatherSystem", "Error encoding search term", e)
            return foundLocations
        }

        // Use device language for localized results
        val language = Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
        val urlString = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedSearchTerm&count=50&language=$language&format=json"

        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            logger.e("WeatherSystem", "Error creating URL", e)
            return foundLocations
        }

        try {
            // Make HTTP GET request to geocoding API
            with(url.openConnection() as HttpURLConnection) {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000
                try {
                    // Use inputStream for success, errorStream for HTTP errors
                    val stream = if (responseCode in 200..299) inputStream else errorStream
                    if (stream == null) return foundLocations

                    // Parse JSON response
                    stream.bufferedReader().use {
                        val response = it.readText()
                        val jsonObject = JSONObject(response)
                        val resultArray = jsonObject.optJSONArray("results") ?: return foundLocations

                        // Extract location data from each result
                        for (i in 0 until resultArray.length()) {
                            val resultObject: JSONObject = resultArray.getJSONObject(i)

                            val latitude = resultObject.optDouble("latitude", Double.NaN)
                            val longitude = resultObject.optDouble("longitude", Double.NaN)
                            if (latitude.isNaN() || longitude.isNaN()) continue

                            // Build location map with all relevant fields
                            foundLocations.add(mapOf(
                                "name" to resultObject.optString("name"),
                                "latitude" to latitude.toString(),
                                "longitude" to longitude.toString(),
                                "country" to resultObject.optString("country", resultObject.optString("country_code","")),
                                "region" to stringUtils.addEndTextIfNotEmpty(resultObject.optString("admin2", resultObject.optString("admin1",resultObject.optString("admin3",""))), ", ")
                            ))
                        }
                    }
                }catch (e: Exception){
                    logger.e("WeatherSystem", "Error searching locations for '$trimmedSearchTerm'", e)
                }
            }
        } catch (e: Exception) {
            logger.e("WeatherSystem", "Error opening connection for location search", e)
        }
        return foundLocations
    }

    /**
     * Fetches current temperature for saved location.
     * Uses Open-Meteo weather API.
     *
     * @return Formatted temperature string with weather icon (e.g., "☀ 22°C"),
     * empty if no location is set, or null if the request failed
     * @must Be called from Dispatchers.IO
     */
    fun getTemp() : String? {

        val tempUnits = sharedPreferenceManager.getTempUnits()
        var currentWeather: String? = null

        val location = sharedPreferenceManager.getWeatherLocation()
        if (location.isEmpty()) return ""

        // Build weather API URL with location and units
        val urlString = "https://api.open-meteo.com/v1/forecast?$location&temperature_unit=${tempUnits}&current=temperature_2m,weather_code"
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            logger.e("WeatherSystem", "Error creating weather URL", e)
            return null
        }

        try {
            // Make HTTP GET request to weather API
            with(url.openConnection() as HttpURLConnection) {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000

                try {
                    // Use appropriate stream based on response code
                    val stream = if (responseCode in 200..299) inputStream else errorStream
                    if (stream == null) return@with

                    stream.bufferedReader().use {
                        val response = it.readText()

                        val jsonObject = JSONObject(response)

                        // Extract current weather data
                        val currentData = jsonObject.optJSONObject("current") ?: return@use

                        // Map WMO weather codes to emoji icons
                        var weatherType = ""

                        when (currentData.optInt("weather_code")) {
                            // Clear sky
                            0, 1 -> {
                                weatherType = "☀\uFE0E" // Sunny
                            }
                            // Partly cloudy / fog
                            2, 3, 45, 48 -> {
                                weatherType = "☁\uFE0E" // Cloudy
                            }
                            // Drizzle / rain
                            51, 53, 55, 56, 57, 61, 63, 65, 67, 80, 81, 82 -> {
                                weatherType = "☂\uFE0E" // Rain
                            }
                            // Snow
                            71, 73, 75, 77, 85, 86 -> {
                                weatherType = "❄\uFE0E" // Snow
                            }
                            // Thunderstorm
                            95, 96, 99 -> {
                                weatherType = "⛈\uFE0E" // Thunder
                            }
                        }

                        // Format temperature with unit
                        val temperature = currentData.optInt("temperature_2m", Int.MIN_VALUE)
                        if (temperature != Int.MIN_VALUE) {
                            currentWeather = "$weatherType $temperature"
                        }

                    }

                } catch(e: Exception) {
                    logger.e("WeatherSystem", "Error fetching weather data", e)
                }
            }
        } catch (e: Exception) {
            logger.e("WeatherSystem", "Error opening weather connection", e)
        }

        val weather = currentWeather ?: return null

        // Append temperature unit based on preference
        return when (tempUnits) {
            "celsius" -> {
                stringUtils.addEndTextIfNotEmpty(weather, "°C")
            }
            "fahrenheit" -> {
                stringUtils.addEndTextIfNotEmpty(weather, "°F")
            }
            else -> {
                ""
            }
        }

    }
}
