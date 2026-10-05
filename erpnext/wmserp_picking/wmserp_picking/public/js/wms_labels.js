// Shared by the Batch and Item list views (hooks.doctype_list_js): "Print QR Label Sheet" puts the
// selected documents on as few A4 pages as the chosen sheet Print Format allows, instead of one page
// per document like the standard Actions > Print.
frappe.provide("wmserp.labels");

wmserp.labels.add_sheet_action = function (listview, doctype) {
	listview.page.add_action_item(__("Print QR Label Sheet"), () => wmserp.labels.print_sheet(listview, doctype));
};

wmserp.labels.print_sheet = function (listview, doctype) {
	const names = listview.get_checked_items(true);
	if (!names.length) {
		frappe.msgprint(__("Select the {0} rows to print first.", [__(doctype)]));
		return;
	}
	frappe.call({ method: "wmserp_picking.api.labels.get_label_sheet_formats", args: { doctype } }).then((r) => {
		const formats = r.message || [];
		if (!formats.length) {
			frappe.msgprint(__("No label sheet Print Format exists for {0}. Run bench migrate to create the shipped one.", [__(doctype)]));
			return;
		}
		const default_format = (formats.find((f) => f.default) || formats[0]).name;
		const dialog = new frappe.ui.Dialog({
			title: __("Print QR labels for {0} {1}", [names.length, __(doctype)]),
			fields: [
				{
					fieldname: "print_format",
					fieldtype: "Select",
					label: __("Label sheet"),
					options: formats.map((f) => f.name),
					default: default_format,
					reqd: 1,
					description: __("Labels per page, label and QR sizes are set in the Print Format itself (duplicate it to keep several layouts)."),
				},
				{
					fieldname: "page_size",
					fieldtype: "Select",
					label: __("Page Size"),
					options: ["A4", "A5", "A3", "Letter", "Legal"],
					default: "A4",
				},
			],
			primary_action_label: __("Print"),
			primary_action(values) {
				dialog.hide();
				const params = {
					doctype,
					names: JSON.stringify(names),
					print_format: values.print_format,
					page_size: values.page_size,
				};
				const query = Object.keys(params)
					.map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(params[key]))
					.join("&");
				const w = window.open("/api/method/wmserp_picking.api.labels.download_label_sheet?" + query);
				if (!w) {
					frappe.msgprint(__("Please enable pop-ups"));
				}
			},
		});
		dialog.show();
	});
};
