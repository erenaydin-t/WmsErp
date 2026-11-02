// One item / warehouse / batch row of a stocktaking session: the manager reviews it here.
const STOCKTAKING_ITEM_API = "wmserp_picking.api.stocktaking.";

frappe.ui.form.on("Stocktaking Item", {
	refresh(frm) {
		if (frm.is_new() || !frm.perm[0].write) return;
		const status = frm.doc.status;
		const reload = () => frm.reload_doc();
		if (["Not Counted", "Assigned", "Counting", "Counted", "Manager Review", "Recounted", "Approved"].includes(status)) {
			frm.add_custom_button(__("Request Recount"), () => {
				frappe.prompt(
					{ fieldname: "note", fieldtype: "Small Text", label: __("Note for the counter") },
					(values) => frappe.call({ method: STOCKTAKING_ITEM_API + "request_recount", args: { item: frm.doc.name, note: values.note }, freeze: true }).then(reload),
					__("Request a recount of {0}", [frm.doc.item_code]),
					__("Request")
				);
			}).addClass(["Manager Review", "Recounted"].includes(status) ? "" : "btn-default");
		}
		if (["Counted", "Manager Review", "Recounted"].includes(status)) {
			frm.add_custom_button(__("Accept Count"), () => {
				frappe.prompt(
					{ fieldname: "note", fieldtype: "Small Text", label: __("Review note (optional)") },
					(values) => frappe.call({ method: STOCKTAKING_ITEM_API + "accept_item", args: { item: frm.doc.name, note: values.note }, freeze: true }).then(reload),
					__("Accept {0} as the final quantity of {1}", [frm.doc.final_qty, frm.doc.item_code]),
					__("Accept")
				);
			}).addClass("btn-primary");
		}
		frm.add_custom_button(__("Count History"), () => frappe.set_route("List", "Stocktaking Count", { stocktaking_item: frm.doc.name }), __("View"));
		frm.add_custom_button(__("Session"), () => frappe.set_route("Form", "Stocktaking Session", frm.doc.session), __("View"));
		render_history(frm);
	},
});

function render_history(frm) {
	frappe.call({ method: STOCKTAKING_ITEM_API + "get_item_history", args: { item: frm.doc.name } }).then((r) => {
		const counts = (r.message && r.message.counts) || [];
		if (!counts.length) return;
		const rows = counts
			.map(
				(c) => `<tr><td>${frappe.utils.escape_html(c.count_type)}</td><td>${format_number(c.qty)}</td><td>${c.difference === null ? "" : format_number(c.difference)}</td>
				<td>${frappe.utils.escape_html(c.counted_by_name || c.counted_by)}</td><td>${frappe.datetime.str_to_user(c.counted_at)}</td><td>${frappe.utils.escape_html(c.outcome || "")}</td></tr>`
			)
			.join("");
		frm.dashboard.add_section(
			`<table class="table table-bordered table-sm"><thead><tr><th>${__("Count")}</th><th>${__("Qty")}</th><th>${__("Difference")}</th><th>${__("By")}</th><th>${__("At")}</th><th>${__("Outcome")}</th></tr></thead><tbody>${rows}</tbody></table>`,
			__("Count History")
		);
	});
}
