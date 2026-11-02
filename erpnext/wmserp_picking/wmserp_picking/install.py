from wmserp_picking.setup.custom_fields import setup_customizations


def after_install():
    setup_customizations()


def after_migrate():
    # Keeps the custom fields in place after `bench migrate` (e.g. after an ERPNext upgrade).
    setup_customizations()
