import os
import sys
import time
import json
import random
import logging
import argparse
import configparser
import hashlib
from fbsmslib import FBSMSLib

def setup_logging(config_level, config_file, args_level):
    """Set up logging based on config and command line arguments."""
    # Command line argument overrides config file
    log_level_str = args_level.upper() if args_level else config_level.upper()

    numeric_level = getattr(logging, log_level_str, None)
    if not isinstance(numeric_level, int):
        print(f"Invalid log level: {log_level_str}. Defaulting to INFO.")
        numeric_level = logging.INFO

    handlers = []
    if config_file:
        handlers.append(logging.FileHandler(config_file))
    else:
        handlers.append(logging.StreamHandler(sys.stdout))

    logging.basicConfig(
        level=numeric_level,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=handlers
    )
    return logging.getLogger("FritzAutoReply")

def parse_config(config_path):
    """Parse the INI configuration file."""
    config = configparser.ConfigParser()
    if not os.path.exists(config_path):
        print(f"Configuration file {config_path} not found.")
        sys.exit(1)

    config.read(config_path)
    return config

def check_and_create_lock(lock_file_path, logger):
    """Check for lock file and create if it doesn't exist or is older than 7 minutes."""
    if os.path.exists(lock_file_path):
        mtime = os.path.getmtime(lock_file_path)
        age = time.time() - mtime
        if age < 7 * 60:
            logger.info(f"Lock file {lock_file_path} exists and is younger than 7 minutes (age: {age:.0f}s). Exiting.")
            sys.exit(0)
        else:
            logger.info(f"Lock file {lock_file_path} is older than 7 minutes (age: {age:.0f}s). Ignoring and replacing.")

    # Create/update lock file
    with open(lock_file_path, 'w') as f:
        f.write(str(time.time()))

def load_state(state_file, logger):
    """Load the state file containing processed message IDs."""
    if os.path.exists(state_file):
        try:
            with open(state_file, 'r') as f:
                state = json.load(f)
                if isinstance(state, list):
                    return set(state)
        except Exception as e:
            logger.error(f"Error reading state file {state_file}: {e}")
    return set()

def save_state(state_file, state, logger):
    """Save the processed message IDs to the state file."""
    try:
        with open(state_file, 'w') as f:
            json.dump(list(state), f)
    except Exception as e:
        logger.error(f"Error writing state file {state_file}: {e}")

def get_message_hash(msg):
    """Generate a unique hash for a message based on sender and date."""
    sender = str(msg.get('sender', ''))
    date = str(msg.get('date', ''))
    text = str(msg.get('text', '')) # also include text to be safe
    unique_string = f"{sender}_{date}_{text}"
    return hashlib.sha256(unique_string.encode('utf-8')).hexdigest()

def release_lock(lock_file_path, logger):
    """Remove the lock file."""
    if os.path.exists(lock_file_path):
        try:
            os.remove(lock_file_path)
            logger.debug(f"Released lock {lock_file_path}")
        except Exception as e:
            logger.error(f"Failed to release lock {lock_file_path}: {e}")

def main():
    parser = argparse.ArgumentParser(description="FritzBox Auto Reply for SMS")
    parser.add_argument('--config', type=str, default='config.ini', help='Path to configuration file')
    parser.add_argument('--loglevel', type=str, help='Override log level (DEBUG, INFO, WARNING, ERROR, CRITICAL)')

    args = parser.parse_args()
    config = parse_config(args.config)

    log_level = config.get('Logging', 'log_level', fallback='INFO')
    log_file = config.get('Logging', 'log_file', fallback='').strip()

    logger = setup_logging(log_level, log_file, args.loglevel)

    logger.info(f"Starting FritzBox Auto Reply using config: {args.config}")

    lock_file = "fritzbox_auto_reply.lock"
    check_and_create_lock(lock_file, logger)

    try:
        state_file = config.get('Settings', 'state_file', fallback='processed_sms.json')
        state = load_state(state_file, logger)
        logger.info(f"Loaded {len(state)} processed messages from {state_file}")

        url = config.get('FritzBox', 'url')
        username = config.get('FritzBox', 'username')
        password = config.get('FritzBox', 'password')
        totpsecret = config.get('FritzBox', 'totpsecret', fallback='').strip()

        sender_match = config.get('Settings', 'sender_match')
        body_match = config.get('Settings', 'body_match')
        target_number = config.get('Settings', 'target_number')
        reply_text = config.get('Settings', 'reply_text')
        min_delay = config.getint('Settings', 'min_delay_seconds', fallback=15)
        max_delay = config.getint('Settings', 'max_delay_seconds', fallback=300)

        if not totpsecret:
            totpsecret = None

        logger.info("Connecting to FritzBox...")
        try:
            fbsms = FBSMSLib(url=url, username=username, password=password, totpsecret=totpsecret)
        except Exception as e:
            logger.error(f"Failed to connect to FritzBox: {e}")
            sys.exit(1)

        logger.info("Fetching incoming messages...")
        try:
            incoming_messages = fbsms.get_sms_incoming()
        except Exception as e:
            logger.error(f"Failed to fetch messages: {e}")
            sys.exit(1)

        logger.info(f"Found {len(incoming_messages)} incoming messages.")

        for msg in incoming_messages:
            msg_hash = get_message_hash(msg)

            if msg_hash in state:
                logger.debug(f"Message {msg_hash} already processed, skipping.")
                continue

            sender = msg.get('sender', '')
            text = msg.get('text', '')

            sender_matches = sender_match in sender
            body_matches = body_match in text

            if sender_matches and body_matches:
                logger.info(f"Message {msg_hash} matched criteria (Sender: {sender}, Text: {text}).")

                delay = random.randint(min_delay, max_delay)
                logger.info(f"Waiting for {delay} seconds before replying...")
                time.sleep(delay)

                try:
                    logger.info(f"Sending reply to {target_number}...")
                    fbsms.send_sms(target_number, reply_text)
                    logger.info(f"Reply sent successfully to {target_number}.")

                    # Mark as processed and save immediately
                    state.add(msg_hash)
                    save_state(state_file, state, logger)

                except Exception as e:
                    logger.error(f"Failed to send SMS to {target_number}: {e}")
            else:
                reason = "Sender and Body mismatch" if not sender_matches and not body_matches else \
                         "Sender mismatch" if not sender_matches else "Body mismatch"
                logger.debug(f"Message {msg_hash} ignored: {reason}. Sender: '{sender}', Text: '{text[:20]}...'")

                # We can also add ignored messages to state so we don't process them again next time, saving time.
                state.add(msg_hash)
                save_state(state_file, state, logger)

    finally:
        release_lock(lock_file, logger)

if __name__ == '__main__':
    main()
