/*
 * Copyright (c) 2024
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.odhil;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;

@Slf4j
class MouseRemapperListener implements MouseListener
{
	// Mouse button numbers for the two side (thumb) buttons.
	// 4/5 is the Windows numbering (XBUTTON1/XBUTTON2); on Linux/X11 the side
	// buttons typically arrive as 6/7 (X11 Button6/Button7), with 4/5 being the wheel.
	private static final int MOUSE_BUTTON_BACK = 4;
	private static final int MOUSE_BUTTON_BACK_LINUX = 6;
	private static final int MOUSE_BUTTON_FORWARD = 5;
	private static final int MOUSE_BUTTON_FORWARD_LINUX = 7;

	private static boolean isBackButton(int button)
	{
		return button == MOUSE_BUTTON_BACK || button == MOUSE_BUTTON_BACK_LINUX;
	}

	private static boolean isForwardButton(int button)
	{
		return button == MOUSE_BUTTON_FORWARD || button == MOUSE_BUTTON_FORWARD_LINUX;
	}

	private static String buttonName(int button)
	{
		if (isBackButton(button))
		{
			return "back";
		}
		if (isForwardButton(button))
		{
			return "forward";
		}
		return "button " + button;
	}

	// Repeat timings for held keys, mimicking keyboard auto-repeat
	private static final long REPEAT_INITIAL_DELAY_MS = 500;
	private static final long REPEAT_PERIOD_MS = 250;

	// Mouse button -> key code currently held
	private final Map<Integer, Integer> heldKeys = new HashMap<>();
	private final Map<Integer, ScheduledFuture<?>> repeaters = new HashMap<>();
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	@Inject
	private Client client;

	@Inject
	private MouseRemapperConfig config;

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		int keyCode = targetKeyCode(mouseEvent.getButton());
		if (keyCode == KeyEvent.VK_UNDEFINED)
		{
			if (isBackButton(mouseEvent.getButton()) || isForwardButton(mouseEvent.getButton()))
			{
				log.debug("{} pressed, no key bound (button {})", buttonName(mouseEvent.getButton()), mouseEvent.getButton());
			}
			return mouseEvent;
		}

		log.debug("{} pressed -> key {}", buttonName(mouseEvent.getButton()), KeyEvent.getKeyText(keyCode));
		postKey(KeyEvent.KEY_PRESSED, mouseEvent.getWhen(), mouseEvent.getModifiersEx(), keyCode);

		if (config.repeatWhenHeld())
		{
			repeaters.put(mouseEvent.getButton(), executor.scheduleAtFixedRate(
				() -> postKey(KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode),
				REPEAT_INITIAL_DELAY_MS, REPEAT_PERIOD_MS, TimeUnit.MILLISECONDS));
		}

		heldKeys.put(mouseEvent.getButton(), keyCode);
		// consume so the game does not also treat the side button as a left click
		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		Integer held = heldKeys.remove(mouseEvent.getButton());
		if (held == null)
		{
			// no tracked press (e.g. plugin toggled or focus changed mid-hold); release the
			// configured key anyway so the game does not think it is still held
			int bound = targetKeyCode(mouseEvent.getButton());
			if (bound == KeyEvent.VK_UNDEFINED)
			{
				if (isBackButton(mouseEvent.getButton()) || isForwardButton(mouseEvent.getButton()))
				{
					log.debug("{} released, nothing held (button {})", buttonName(mouseEvent.getButton()), mouseEvent.getButton());
				}
				return mouseEvent;
			}
			held = bound;
		}

		log.debug("{} released -> key {}", buttonName(mouseEvent.getButton()), KeyEvent.getKeyText(held));

		ScheduledFuture<?> repeater = repeaters.remove(mouseEvent.getButton());
		if (repeater != null)
		{
			repeater.cancel(false);
		}

		postKey(KeyEvent.KEY_RELEASED, mouseEvent.getWhen(), mouseEvent.getModifiersEx(), held);
		// consume so the release is not passed to the game as a click either
		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	/**
	 * @return the configured key code for a mouse button, or {@link KeyEvent#VK_UNDEFINED} if none
	 */
	private int targetKeyCode(int button)
	{
		if (isBackButton(button))
		{
			return config.sideButtonBack().getKeyCode();
		}
		if (isForwardButton(button))
		{
			return config.sideButtonForward().getKeyCode();
		}
		return KeyEvent.VK_UNDEFINED;
	}

	/**
	 * Dispatches a synthetic key event to the currently focused component,
	 * so it is handled by the game exactly like a real key press.
	 */
	private void postKey(int id, long when, int modifiers, int keyCode)
	{
		Component source = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (source == null)
		{
			source = client.getCanvas();
		}
		if (source == null)
		{
			log.debug("no focus owner and no canvas; dropping key event {}", KeyEvent.getKeyText(keyCode));
			return;
		}

		log.debug("{} key {} -> {}", id == KeyEvent.KEY_PRESSED ? "pressing" : "releasing", KeyEvent.getKeyText(keyCode), source.getClass().getSimpleName());

		final Component target = source;
		KeyEvent keyEvent = new KeyEvent(target, id, when, modifiers, keyCode, keyCharFor(keyCode));
		// dispatchEvent must be called on the event dispatch thread
		if (EventQueue.isDispatchThread())
		{
			target.dispatchEvent(keyEvent);
		}
		else
		{
			SwingUtilities.invokeLater(() -> target.dispatchEvent(keyEvent));
		}
	}

	/**
	 * @return the character associated with a key code, or {@link KeyEvent#CHAR_UNDEFINED} for non-character keys
	 */
	private char keyCharFor(int keyCode)
	{
		String text = KeyEvent.getKeyText(keyCode);
		if (text.length() == 1)
		{
			return text.charAt(0);
		}
		return KeyEvent.CHAR_UNDEFINED;
	}

	/**
	 * Stops all repeating keys and cancels the executor. Called when the plugin shuts down.
	 */
	void stopAll()
	{
		repeaters.values().forEach(f -> f.cancel(false));
		repeaters.clear();
		heldKeys.clear();
		executor.shutdownNow();
	}
}
