#!/usr/bin/env perl
########################################################################
# adpated for WS 0.1.0+ by Michael Sneddon, LBL
# Original authors: Christopher Henry, Scott Devoid, Paul Frybarger
# Contact email: mwsneddon@lbl.gov or chenry@mcs.anl.gov
########################################################################
use strict;
use warnings;
use Getopt::Long::Descriptive;
use Text::Table;
use Bio::KBase::workspace::ScriptHelpers qw(get_ws_client workspace getObjectRef parseObjectMeta parseWorkspaceMeta);

my $serv = get_ws_client();
#Defining globals describing behavior
my $primaryArgs = ["Object ID or Name"];
my $translation = {
	"Object ID or Name" => "id",
	workspace => "workspace"
};
#Defining usage and options
my ($opt, $usage) = describe_options(
    'ws-delete <'.join("> <",@{$primaryArgs}).'> %o',
    [ 'workspace|w:s', 'ID or Name of workspace', {"default" => workspace()} ],
    [ 'restore', 'Restore the specified deleted object', {"default" => 0} ],
    [ 'showerror|e', 'Show full stack trace of any errors in execution',{"default"=>0}],
    [ 'help|h|?', 'Print this usage information' ]  
);
$usage = "\nNAME\n  ws-delete -- delete/undelete an object\n\nSYNOPSIS\n  ".$usage;
$usage .= "\n";
if (defined($opt->{help})) {
	print $usage;
	exit;
}
#Processing primary arguments
if (scalar(@ARGV) > scalar(@{$primaryArgs})) {
	print STDERR "Too many input arguments given.  Run with -h or --help for usage information\n";
	exit 1;
}
foreach my $arg (@{$primaryArgs}) {
	$opt->{$arg} = shift @ARGV;
	if (!defined($opt->{$arg})) {
		print STDERR "Not enough input arguments provided.  Run with -h or --help for usage information\n";
		exit 1;
	}
}
#Instantiating parameters
my $params = [{
	      ref => getObjectRef($opt->{workspace},$opt->{"Object ID or Name"},undef),
	      }];

#Calling the server
my $output;
if ($opt->{restore}) {
	if ($opt->{showerror} == 0){
		eval { $serv->undelete_objects($params); };
		if($@) {
			print "Cannot restore object!\n";
			print STDERR $@->{message}."\n";
			if(defined($@->{status_line})) {print STDERR $@->{status_line}."\n" };
			print STDERR "\n";
			exit 1;
		}
	} else {
		$serv->undelete_objects($params);
	}
	print "Object successfully restored.\n";
} else {
	
	if ($opt->{showerror} == 0){
		eval { $serv->delete_objects($params); };
		if($@) {
			print "Cannot delete object!\n";
			print STDERR $@->{message}."\n";
			if(defined($@->{status_line})) {print STDERR $@->{status_line}."\n" };
			print STDERR "\n";
			exit 1;
		}
	} else {
		$serv->delete_objects($params);
	}
	print "Object successfully deleted.\n";
}
exit 0;
