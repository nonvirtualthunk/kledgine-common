package arx.display.windowing

data object RefreshEntities: WidgetEvent(null) {
    override val propagate: PropagationDirection = PropagationDirection.Down
}