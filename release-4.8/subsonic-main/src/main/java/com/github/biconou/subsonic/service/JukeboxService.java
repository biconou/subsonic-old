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
package com.github.biconou.subsonic.service;

import org.slf4j.LoggerFactory;

import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.domain.MediaFile;
import net.sourceforge.subsonic.domain.PlayQueue;
import net.sourceforge.subsonic.domain.Player;
import net.sourceforge.subsonic.domain.TransferStatus;
import net.sourceforge.subsonic.domain.User;
import net.sourceforge.subsonic.service.AudioScrobblerService;
import net.sourceforge.subsonic.service.IJukeboxService;
import net.sourceforge.subsonic.service.MediaFileService;
import net.sourceforge.subsonic.service.SecurityService;
import net.sourceforge.subsonic.service.SettingsService;
import net.sourceforge.subsonic.service.StatusService;
import net.sourceforge.subsonic.service.TranscodingService;
import net.sourceforge.subsonic.service.jukebox.AudioPlayer;
import net.sourceforge.subsonic.util.FileUtil;

import com.github.biconou.AudioPlayer.PlayerListener;

/**
 * Plays music on the local audio device.
 *
 * @author Sindre Mehus / Rémi Cocula
 */
public class JukeboxService implements AudioPlayer.Listener,PlayerListener,IJukeboxService {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(JukeboxService.class);

    private com.github.biconou.AudioPlayer.Player audioPlayer;
    private TranscodingService transcodingService;
    private AudioScrobblerService audioScrobblerService;
    private StatusService statusService;
    private SettingsService settingsService;
    private SecurityService securityService;

    private Player player;
    private TransferStatus status;
    private MediaFile currentPlayingFile;
    private float gain = 0.5f;
    private int offset;
    private MediaFileService mediaFileService;

    /**
     * Updates the jukebox by starting or pausing playback on the local audio device.
     *
     * @param player The player in question.
     * @param offset Start playing after this many seconds into the track.
     */
    public synchronized void updateJukebox(Player player, int offset) throws Exception {
    	
    	LOG.debug("Begin updateJukebox");
    	
    	// Test if current user is authorized for jukebox.
        User user = securityService.getUserByName(player.getUsername());
        if (!user.isJukeboxRole()) {
            LOG.warn("{} is not authorized for jukebox playback.",user.getUsername());
            return;
        }

        LOG.debug("player.getPlayQueue().getStatus() : {} ",player.getPlayQueue().getStatus());
        
        if (player.getPlayQueue().getStatus() == PlayQueue.Status.PLAYING) {
        	LOG.debug("updateJukebox Playing");
            this.player = player;
            MediaFile result;
            synchronized (player.getPlayQueue()) {
                result = player.getPlayQueue().getCurrentFile();
            }
            play(result, offset);
        } else {
        	LOG.debug("updateJukebox Pause");
            if (audioPlayer != null) {
            	if (currentPlayingFile != null) {
            		audioPlayer.pause();
            	}
            }
        }
    }

    /**
     * 
     * @param file
     * @param offset
     * @throws Exception 
     */
    private synchronized void play(MediaFile file, int offset) throws Exception {
        
    	LOG.debug("Begin play()");
        try {
        	
        	LOG.debug("audioPlayer state : {}",audioPlayer==null?"-":audioPlayer.getState());
        	
            // Resume if possible.
            boolean sameFile = file != null && file.equals(currentPlayingFile);
            boolean paused = audioPlayer != null && audioPlayer.getState() == com.github.biconou.AudioPlayer.Player.State.PAUSED;
            if (sameFile && paused && offset == 0) {
            	LOG.debug("invoque audioPlayer.play()");
                audioPlayer.play();
            } else { // Start playing new file
            	LOG.debug("Start playing new file");
                
            	// TODO : offset is unused 
            	this.offset = offset;
            	
            	// If an audio player exists it is closed to recreate a new one. 
            	// This is done to avoid some bugs by reseting 
            	// TODO  but is it the best way ? 
                if (audioPlayer != null) {
                	LOG.debug("Closing the existing audio player.");
                	try {
                		audioPlayer.close();
                	} catch (Exception e) {
                		// Nothing to do
                	} finally {
                		audioPlayer = null;
                	}
                    
                    
//                    if (currentPlayingFile != null) {
//                        onSongEnd(currentPlayingFile);
//                    }
                }

                if (file != null) {
                    //int duration = file.getDurationSeconds() == null ? 0 : file.getDurationSeconds() - offset;
                    //TranscodingService.Parameters parameters = new TranscodingService.Parameters(file, new VideoTranscodingSettings(0, 0, offset, duration, false));
                    //String command = settingsService.getJukeboxCommand();
                    //parameters.setTranscoding(new Transcoding(null, null, null, null, command, null, null, false));
                    //in = transcodingService.getTranscodedInputStream(parameters);
                	
                	if (audioPlayer == null) {
                		LOG.debug("Creating a new audio player.");
                		audioPlayer = new com.github.biconou.AudioPlayer.MPlayerPlayer(new Float(gain));
                    	audioPlayer.registerListener(this);
                	}
                	LOG.debug("Setting play list to the new audio player.");
                	audioPlayer.setPlayList(this.player.getPlayQueue());
                	audioPlayer.play();

                    onSongStart(file);
                    currentPlayingFile = file;
                    LOG.debug("The current playing file is now {}",currentPlayingFile.getName());
                }
            } // END Start playing new file
        } catch (Exception x) {
            LOG.error("Error in jukebox: " + x, x);
            throw x;
            //IOUtils.closeQuietly(in);
        }
    }

    public synchronized void stateChanged(AudioPlayer audioPlayer, AudioPlayer.State state) {
//        if (state == EOM) {
//            player.getPlayQueue().next();
//            MediaFile result;
//            synchronized (player.getPlayQueue()) {
//                result = player.getPlayQueue().getCurrentFile();
//            }
//            play(result, 0);
//        }
    }
    
    /**
     * This method is invoked as a reflex when the audio player has changed audio stream.
     */
    public void nextStreamNotified() {
    	LOG.debug("Begin nextStreamNotified()");
    	
    	onSongEnd(currentPlayingFile);
    	currentPlayingFile = null;
    	player.getPlayQueue().next();
    	MediaFile result;
    	synchronized (player.getPlayQueue()) {
    		result = player.getPlayQueue().getCurrentFile();
    	}
    	
    	if (result != null) {
    		LOG.debug("The next song is {}",result.getName());
	    	onSongStart(result);
	        currentPlayingFile = result;
    	} else {
    		LOG.debug("No next song in queue");
    	}
    }
    
    /**
     * 
     */
	public void endNotified() {
		onSongEnd(currentPlayingFile);
		currentPlayingFile = null;
	}



    public synchronized float getGain() {
        return gain;
    }

    public synchronized int getPosition() {
        //return audioPlayer == null ? 0 : offset + audioPlayer.getPosition();
    	return 0;
    }

    /**
     * Returns the player which currently uses the jukebox.
     *
     * @return The player, may be {@code null}.
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * 
     * @param file
     */
    private void onSongStart(MediaFile file) {
    	LOG.debug("begin onSongStart");
    	
        LOG.debug("{} starting jukebox for \"{}\"",player.getUsername(),FileUtil.getShortPath(file.getFile()));
        status = statusService.createStreamStatus(player);
        status.setFile(file.getFile());
        status.addBytesTransfered(file.getFileSize());
        mediaFileService.incrementPlayCount(file);
        scrobble(file, false);
    }

    private void onSongEnd(MediaFile file) {
        LOG.info(player.getUsername() + " stopping jukebox for \"" + FileUtil.getShortPath(file.getFile()) + "\"");
        if (status != null) {
            statusService.removeStreamStatus(status);
        }
        scrobble(file, true);
    }

    private void scrobble(MediaFile file, boolean submission) {
        if (player.getClientId() == null) {  // Don't scrobble REST players.
            audioScrobblerService.register(file, player.getUsername(), submission, null);
        }
    }

    public synchronized void setGain(float gain) {
        this.gain = gain;
        if (audioPlayer != null) {
           audioPlayer.setGain(gain);
        }
    }

    public void setTranscodingService(TranscodingService transcodingService) {
        this.transcodingService = transcodingService;
    }

    public void setAudioScrobblerService(AudioScrobblerService audioScrobblerService) {
        this.audioScrobblerService = audioScrobblerService;
    }

    public void setStatusService(StatusService statusService) {
        this.statusService = statusService;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setSecurityService(SecurityService securityService) {
        this.securityService = securityService;
    }

    public void setMediaFileService(MediaFileService mediaFileService) {
        this.mediaFileService = mediaFileService;
    }

}
