package canaryprism.presence.apple.music;

public sealed interface TrackContainer permits RealTrack, StoredTrack {
    
    default int getTrackId() {
        return switch (this) {
            case RealTrack(var track) -> track.getId();
            case StoredTrack(var id) -> id;
        };
    }
}
