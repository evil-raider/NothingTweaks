    fun getInt(key: String, default: Int): Int {
        maybeReload()
        return try {
            delegate.getInt(key, default)
        } catch (t: Throwable) {
            try {
                delegate.getString(key, "")?.toIntOrNull() ?: default
            } catch (t2: Throwable) {
                default
            }
        }
    }
