"""Pushing a device's events to a person: Spug and WeCom group robots.

`channels.py` knows the two HTTP APIs and nothing about devices; `notifier.py`
decides what is worth a message and owns the queue that keeps delivery out of
the device's request.
"""
