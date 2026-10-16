package brightside.peers;

/**
 * Where one person's map settings are kept. Loading never fails: nothing
 * stored, or anything unreadable, is {@link MapSettings#DEFAULT}.
 */
public interface MapSettingsStore {

	MapSettings load();

	void save(MapSettings settings);

	/** Settings that last only as long as the process: before the venue is up, and in tests. */
	static MapSettingsStore memory() {
		return new MapSettingsStore() {
			private MapSettings held = MapSettings.DEFAULT;

			@Override
			public synchronized MapSettings load() {
				return held;
			}

			@Override
			public synchronized void save(MapSettings settings) {
				held = settings;
			}
		};
	}
}
