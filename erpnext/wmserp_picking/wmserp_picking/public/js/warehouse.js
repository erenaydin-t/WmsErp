// Warehouse form: "Print QR Labels" opens the WMS QR Labels page for this warehouse (every item /
// batch in stock, one item or one batch, on a label printer).
frappe.ui.form.on("Warehouse", {
	refresh(frm) {
		if (frm.is_new()) return;
		const open = (mode) => {
			frappe.route_options = { warehouse: frm.doc.name, mode };
			frappe.set_route("wms-qr-labels");
		};
		frm.add_custom_button(__("All Items in Stock"), () => open("warehouse"), __("Print QR Labels"));
		frm.add_custom_button(__("One Item"), () => open("item"), __("Print QR Labels"));
		frm.add_custom_button(__("One Batch"), () => open("batch"), __("Print QR Labels"));
	},
});
