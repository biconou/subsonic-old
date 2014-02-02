/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.domain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.apache.commons.lang.StringUtils;


import com.github.biconou.AudioPlayer.PlayList;


/**
 * A playlist is a list of music files that are associated to a remote player.
 *
 * @author Sindre Mehus
 */
public class PlayQueue implements PlayList {

    private List<MediaFile> files = new ArrayList<MediaFile>();
    private boolean repeatEnabled;
    private String name = "(unnamed)";
    private Status status = Status.PLAYING;
    private RandomSearchCriteria randomSearchCriteria;

    /**
     * The index of the current song, or -1 is the end of the playlist is reached.
     * Note that both the index and the playlist size can be zero.
     */
    private int index = 0;
    
    
    private int indexAudioStreams = -1;

    /**
     * Used for undo functionality.
     */
    private List<MediaFile> filesBackup = new ArrayList<MediaFile>();
    private int indexBackup = 0;

    /**
     * Returns the user-defined name of the playlist.
     *
     * @return The name of the playlist, or <code>null</code> if no name has been assigned.
     */
    public synchronized String getName() {
        return name;
    }

    /**
     * Sets the user-defined name of the playlist.
     *
     * @param name The name of the playlist.
     */
    public synchronized void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the current song in the playlist.
     *
     * @return The current song in the playlist, or <code>null</code> if no current song exists.
     */
    public synchronized MediaFile getCurrentFile() {
        if (index == -1 || index == 0 && size() == 0) {
            setStatus(Status.STOPPED);
            return null;
        } else {
            MediaFile file = files.get(index);

            // Remove file from playlist if it doesn't exist.
            if (!file.exists()) {
                files.remove(index);
                index = Math.max(0, Math.min(index, size() - 1));
                return getCurrentFile();
            }

            return file;
        }
    }

    /**
     * Returns all music files in the playlist.
     *
     * @return All music files in the playlist.
     */
    public synchronized List<MediaFile> getFiles() {
        return files;
    }

    /**
     * Returns the music file at the given index.
     *
     * @param index The index.
     * @return The music file at the given index.
     * @throws IndexOutOfBoundsException If the index is out of range.
     */
    public synchronized MediaFile getFile(int index) {
        return files.get(index);
    }

    /**
     * Skip to the next song in the playlist.
     */
    public synchronized void next() {
        index++;

        // Reached the end?
        if (index >= size()) {
            index = isRepeatEnabled() ? 0 : -1;
        }
    }

    /**
     * Returns the number of songs in the playlists.
     *
     * @return The number of songs in the playlists.
     */
    public synchronized int size() {
        return files.size();
    }

    /**
     * Returns whether the playlist is empty.
     *
     * @return Whether the playlist is empty.
     */
    public synchronized boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * Returns the index of the current song.
     *
     * @return The index of the current song, or -1 if the end of the playlist is reached.
     */
    public synchronized int getIndex() {
        return index;
    }

    /**
     * Sets the index of the current song.
     *
     * @param index The index of the current song.
     */
    public synchronized void setIndex(int index) {
        makeBackup();
        this.index = Math.max(0, Math.min(index, size() - 1));
        setStatus(Status.PLAYING);
    }

    /**
     * Adds one or more music file to the playlist.
     *
     * @param append     Whether existing songs in the playlist should be kept.
     * @param mediaFiles The music files to add.
     * @throws IOException If an I/O error occurs.
     */
    public synchronized void addFiles(boolean append, Iterable<MediaFile> mediaFiles) throws IOException {
        makeBackup();
        if (!append) {
            index = 0;
            files.clear();
        }
        for (MediaFile mediaFile : mediaFiles) {
            files.add(mediaFile);
        }
        setStatus(Status.PLAYING);
    }

    /**
     * Convenience method, equivalent to {@link #addFiles(boolean, Iterable)}.
     */
    public synchronized void addFiles(boolean append, MediaFile... mediaFiles) throws IOException {
        addFiles(append, Arrays.asList(mediaFiles));
    }

    /**
     * Removes the music file at the given index.
     *
     * @param index The playlist index.
     */
    public synchronized void removeFileAt(int index) {
        makeBackup();
        index = Math.max(0, Math.min(index, size() - 1));
        if (this.index > index) {
            this.index--;
        }
        files.remove(index);

        if (index != -1) {
            this.index = Math.max(0, Math.min(this.index, size() - 1));
        }
    }

    /**
     * Clears the playlist.
     */
    public synchronized void clear() {
        makeBackup();
        files.clear();
        index = 0;
    }

    /**
     * Shuffles the playlist.
     */
    public synchronized void shuffle() {
        makeBackup();
        MediaFile currentFile = getCurrentFile();
        Collections.shuffle(files);
        if (currentFile != null) {
            index = files.indexOf(currentFile);
        }
    }

    /**
     * Sorts the playlist according to the given sort order.
     */
    public synchronized void sort(final SortOrder sortOrder) {
        makeBackup();
        MediaFile currentFile = getCurrentFile();

        Comparator<MediaFile> comparator = new Comparator<MediaFile>() {
            public int compare(MediaFile a, MediaFile b) {
                switch (sortOrder) {
                    case TRACK:
                        Integer trackA = a.getTrackNumber();
                        Integer trackB = b.getTrackNumber();
                        if (trackA == null) {
                            trackA = 0;
                        }
                        if (trackB == null) {
                            trackB = 0;
                        }
                        return trackA.compareTo(trackB);

                    case ARTIST:
                        String artistA = StringUtils.trimToEmpty(a.getArtist());
                        String artistB = StringUtils.trimToEmpty(b.getArtist());
                        return artistA.compareTo(artistB);

                    case ALBUM:
                        String albumA = StringUtils.trimToEmpty(a.getAlbumName());
                        String albumB = StringUtils.trimToEmpty(b.getAlbumName());
                        return albumA.compareTo(albumB);
                    default:
                        return 0;
                }
            }
        };

        Collections.sort(files, comparator);
        if (currentFile != null) {
            index = files.indexOf(currentFile);
        }
    }

    /**
     * Moves the song at the given index one step up.
     *
     * @param index The playlist index.
     */
    public synchronized void moveUp(int index) {
        makeBackup();
        if (index <= 0 || index >= size()) {
            return;
        }
        Collections.swap(files, index, index - 1);

        if (this.index == index) {
            this.index--;
        } else if (this.index == index - 1) {
            this.index++;
        }
    }

    /**
     * Moves the song at the given index one step down.
     *
     * @param index The playlist index.
     */
    public synchronized void moveDown(int index) {
        makeBackup();
        if (index < 0 || index >= size() - 1) {
            return;
        }
        Collections.swap(files, index, index + 1);

        if (this.index == index) {
            this.index++;
        } else if (this.index == index + 1) {
            this.index--;
        }
    }

    /**
     * Returns whether the playlist is repeating.
     *
     * @return Whether the playlist is repeating.
     */
    public synchronized boolean isRepeatEnabled() {
        return repeatEnabled;
    }

    /**
     * Sets whether the playlist is repeating.
     *
     * @param repeatEnabled Whether the playlist is repeating.
     */
    public synchronized void setRepeatEnabled(boolean repeatEnabled) {
        this.repeatEnabled = repeatEnabled;
    }

    /**
     * Revert the last operation.
     */
    public synchronized void undo() {
        List<MediaFile> filesTmp = new ArrayList<MediaFile>(files);
        int indexTmp = index;

        index = indexBackup;
        files = filesBackup;

        indexBackup = indexTmp;
        filesBackup = filesTmp;
    }

    /**
     * Returns the playlist status.
     *
     * @return The playlist status.
     */
    public synchronized Status getStatus() {
        return status;
    }

    /**
     * Sets the playlist status.
     *
     * @param status The playlist status.
     */
    public synchronized void setStatus(Status status) {
        this.status = status;
        if (index == -1) {
            index = Math.max(0, Math.min(index, size() - 1));
        }
    }

    /**
     * Returns the criteria used to generate this random playlist.
     *
     * @return The search criteria, or <code>null</code> if this is not a random playlist.
     */
    public synchronized RandomSearchCriteria getRandomSearchCriteria() {
        return randomSearchCriteria;
    }

    /**
     * Sets the criteria used to generate this random playlist.
     *
     * @param randomSearchCriteria The search criteria, or <code>null</code> if this is not a random playlist.
     */
    public synchronized void setRandomSearchCriteria(RandomSearchCriteria randomSearchCriteria) {
        this.randomSearchCriteria = randomSearchCriteria;
    }

    /**
     * Returns the total length in bytes.
     *
     * @return The total length in bytes.
     */
    public synchronized long length() {
        long length = 0;
        for (MediaFile mediaFile : files) {
            length += mediaFile.getFileSize();
        }
        return length;
    }

    private void makeBackup() {
        filesBackup = new ArrayList<MediaFile>(files);
        indexBackup = index;
    }

    /**
     * Playlist status.
     */
    public enum Status {
        PLAYING,
        STOPPED
    }

    /**
     * Playlist sort order.
     */
    public enum SortOrder {
        TRACK,
        ARTIST,
        ALBUM
    }

	public AudioInputStream getCurrentAudioStream() {
		MediaFile file = getCurrentFile();
		
		if (file != null) {
			try {
				return AudioSystem.getAudioInputStream(file.getFile());
			} catch (UnsupportedAudioFileException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * 
	 */
	public AudioInputStream getFirstAudioStream() {
		
		indexAudioStreams = index;
		MediaFile file = getCurrentFile();
		
		try {
			return AudioSystem.getAudioInputStream(file.getFile());
		} catch (UnsupportedAudioFileException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 
	 */
	public AudioInputStream getNextAudioStream() {
		
		if (indexAudioStreams == -1) {
			throw new IllegalStateException("Call getFirstAudioStream() first.");
		}
		
		indexAudioStreams++;

        // Reached the end?
        if (indexAudioStreams >= size()) {
            return null;
        } else {
    		try {
    			return AudioSystem.getAudioInputStream(files.get(indexAudioStreams).getFile());
    		} catch (UnsupportedAudioFileException e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		} catch (IOException e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		}        	
        }
		return null;
	}

	/**
	 * 
	 */
	public String getFirstAudioFileName() {
		indexAudioStreams = index;
		MediaFile file = getCurrentFile();
		return file.getFile().getAbsolutePath();
	}

	/**
	 * 
	 */
	public String getNextAudioFileName() {
		if (indexAudioStreams == -1) {
			throw new IllegalStateException("Call getFirstAudioStream() first.");
		}
		
		indexAudioStreams++;

        // Reached the end?
        if (indexAudioStreams >= size()) {
            return null;
        } else {
    		return files.get(indexAudioStreams).getFile().getAbsolutePath();
        }
	}

	/**
	 * 
	 */
	public String getCurrentAudioFileName() {
		MediaFile file = getCurrentFile();

		if (file != null) {
			return file.getFile().getAbsolutePath();
		}
		return null;
	}
}