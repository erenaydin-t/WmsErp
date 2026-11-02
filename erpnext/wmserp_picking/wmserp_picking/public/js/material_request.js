// Material Request > Create > "Pick List" for Material Issue requests too.
// ERPNext only offers the button on Material Transfer requests; the WMS app adds "Material Issue"
// to the Pick List purposes (Property Setter) and maps the request through its own method, which
// picks from each row's For Warehouse. Loaded by hooks.doctype_js next to ERPNext's own form script.
frappe.ui.form.on("Material Request", {
	refresh(frm) {
		if (frm.doc.docstatus !== 1 || frm.doc.status === "Stopped") return;
		if (frm.doc.material_request_type !== "Material Issue") return;
		const precision = frappe.defaults.get_default("float_precision");
		if (flt(frm.doc.per_ordered, precision) >= 100) return;
		if (!(frm.doc.items || []).some((item) => flt(item.stock_qty) - flt(item.picked_qty) > 0)) return;
		frm.add_custom_button(
			__("Pick List"),
			() => {
				frappe.model.open_mapped_doc({
					method: "wmserp_picking.api.pick_list.create_pick_list_from_material_request",
					frm: frm,
				});
			},
			__("Create")
		);
	},
});
